<?php
/**
 * Plugin Name: Phone TV Share Cards
 * Description: Gives every Phone TV share link its own preview card (title, picture, a line about it) on Facebook, X and messaging apps. Serves the share page at /tv/.
 * Version: 1.0.0
 * Author: zGenMedia
 * License: GPL-3.0-or-later
 *
 * Why this exists
 * ---------------
 * Social sites build a link's preview by reading the page's <meta> tags, and they do not
 * run scripts. Phone TV's share page is a static file, so by itself every link would get
 * the same card. This plugin answers yoursite.com/tv/?t=…&id=…&n=… with that same page,
 * after filling in the card for the thing being shared: a film's poster, an actor's face,
 * a studio's logo.
 *
 * It touches nothing else on the site: no database tables, no settings, no admin screens.
 * It stores only short-lived cached copies of the page and of film descriptions, and
 * nothing about who shares or opens a link.
 */

if (!defined('ABSPATH')) {
    exit;
}

const PHONETV_SHARE_PATH  = 'tv';
const PHONETV_SHARE_PAGE  = 'https://zgenmedia.github.io/phone-tv/s/index.html';
const PHONETV_SHARE_IMAGE = 'https://zgenmedia.github.io/phone-tv/assets/og.jpg';

/** The image hosts a link may point a card at. The app and the page hold the same list. */
function phonetv_share_image_hosts() {
    return array('commons.wikimedia.org', 'upload.wikimedia.org', 'static.tvmaze.com', 'images.metahub.space');
}

// Early, before WordPress goes looking for a post called "tv".
add_action('parse_request', 'phonetv_share_maybe_serve', 1);

function phonetv_share_maybe_serve() {
    $path = trim((string) parse_url(isset($_SERVER['REQUEST_URI']) ? $_SERVER['REQUEST_URI'] : '', PHP_URL_PATH), '/');
    $home = trim((string) parse_url(home_url(), PHP_URL_PATH), '/');
    if ($home !== '' && strpos($path, $home) === 0) {
        $path = trim(substr($path, strlen($home)), '/');
    }
    if ($path !== PHONETV_SHARE_PATH) {
        return;
    }

    $page = phonetv_share_page();
    $query = isset($_SERVER['QUERY_STRING']) ? $_SERVER['QUERY_STRING'] : '';
    if ($page === '') {
        // The page can't be fetched right now: send people to the original, which works without a card.
        wp_redirect('https://zgenmedia.github.io/phone-tv/s/' . ($query !== '' ? '?' . $query : ''), 302);
        exit;
    }

    $subject = phonetv_share_read();
    if ($subject) {
        $card = phonetv_share_describe($subject);
        $here = home_url('/' . PHONETV_SHARE_PATH . '/') . ($query !== '' ? '?' . $query : '');
        $page = phonetv_share_fill($page, $card, $here);
    }

    if (!defined('DONOTCACHEPAGE')) {
        define('DONOTCACHEPAGE', true); // each link is its own page; a page cache would serve one card for all
    }
    status_header(200);
    nocache_headers();
    header('Content-Type: text/html; charset=utf-8');
    header('X-Robots-Tag: noindex');
    echo $page; // phpcs:ignore -- our own page, with every inserted value escaped in phonetv_share_fill()
    exit;
}

/** The share page itself, fetched from Phone TV's site and kept for ten minutes. */
function phonetv_share_page() {
    $cached = get_transient('phonetv_share_page');
    if (is_string($cached) && $cached !== '') {
        return $cached;
    }
    $reply = wp_remote_get(PHONETV_SHARE_PAGE, array('timeout' => 8));
    if (is_wp_error($reply) || wp_remote_retrieve_response_code($reply) !== 200) {
        return '';
    }
    $html = (string) wp_remote_retrieve_body($reply);
    if (strpos($html, 'og:title') === false) {
        return '';
    }
    set_transient('phonetv_share_page', $html, 10 * MINUTE_IN_SECONDS);
    return $html;
}

/**
 * Reads and checks the link. A link names a subject and nothing else: a kind, a public
 * catalog id, a name and, for a person or company, a picture from a known host.
 */
function phonetv_share_read() {
    $get  = wp_unslash($_GET); // phpcs:ignore -- read-only, validated below
    $type = isset($get['t']) ? (string) $get['t'] : '';
    $id   = isset($get['id']) ? (string) $get['id'] : '';
    $name = isset($get['n']) ? trim(wp_strip_all_tags((string) $get['n'])) : '';
    $name = function_exists('mb_substr') ? mb_substr($name, 0, 120) : substr($name, 0, 120);

    if ($type === 'movie' || $type === 'series') {
        $ok = (bool) preg_match('/^tt\d{5,10}$/', $id);
    } elseif ($type === 'company') {
        $ok = preg_match('/^Q\d{1,10}$/', $id) && $name !== '';
    } elseif ($type === 'person') {
        $ok = $id === '' ? $name !== '' : (bool) preg_match('/^Q\d{1,10}$/', $id);
    } else {
        $ok = false;
    }
    if (!$ok) {
        return null;
    }

    $image = '';
    $sent  = isset($get['i']) ? (string) $get['i'] : '';
    $parts = $sent !== '' ? wp_parse_url($sent) : false;
    if ($parts && isset($parts['scheme'], $parts['host']) && $parts['scheme'] === 'https'
        && in_array($parts['host'], phonetv_share_image_hosts(), true)) {
        $image = esc_url_raw($sent, array('https'));
    }

    return array(
        'type'    => $type,
        'id'      => $id,
        'name'    => $name,
        'image'   => $image,
        'network' => isset($get['k']) && $get['k'] === 'network',
    );
}

/** The card for one subject: title, a line about it, and its picture. */
function phonetv_share_describe($s) {
    if ($s['type'] === 'movie' || $s['type'] === 'series') {
        $card = array(
            'title'       => ($s['name'] !== '' ? $s['name'] : 'Something to watch') . ' · on Phone TV',
            'description' => ($s['type'] === 'movie' ? 'Movie' : 'Show') . ' shared from Phone TV, the free TV home screen for phones, TV boxes, PCs and Macs.',
            'image'       => 'https://images.metahub.space/background/medium/' . $s['id'] . '/img',
        );
        // The open catalog's own line about the title makes a better card than ours. Kept for a day.
        $key  = 'phonetv_meta_' . $s['id'];
        $meta = get_transient($key);
        if (!is_array($meta)) {
            $meta  = array();
            $reply = wp_remote_get('https://v3-cinemeta.strem.io/meta/' . $s['type'] . '/' . $s['id'] . '.json', array('timeout' => 5));
            if (!is_wp_error($reply) && wp_remote_retrieve_response_code($reply) === 200) {
                $json = json_decode((string) wp_remote_retrieve_body($reply), true);
                if (isset($json['meta']) && is_array($json['meta'])) {
                    $meta = array(
                        'name' => isset($json['meta']['name']) ? (string) $json['meta']['name'] : '',
                        'year' => isset($json['meta']['releaseInfo']) ? (string) $json['meta']['releaseInfo'] : '',
                        'text' => isset($json['meta']['description']) ? (string) $json['meta']['description'] : '',
                    );
                    set_transient($key, $meta, DAY_IN_SECONDS);
                }
            }
        }
        if (!empty($meta['name'])) {
            $card['title'] = $meta['name'] . (!empty($meta['year']) ? ' (' . $meta['year'] . ')' : '') . ' · on Phone TV';
        }
        if (!empty($meta['text'])) {
            $card['description'] = wp_html_excerpt($meta['text'], 187, '…');
        }
        return $card;
    }

    $about = $s['type'] === 'person'
        ? 'Everything ' . $s['name'] . ' has been in, in one place.'
        : 'Everything from ' . $s['name'] . ($s['network'] ? ', the network' : '') . ', in one place.';
    return array(
        'title'       => $s['name'] . ' · on Phone TV',
        'description' => $about . ' Shared from Phone TV, the free TV home screen.',
        'image'       => $s['image'] !== '' ? $s['image'] : PHONETV_SHARE_IMAGE,
    );
}

/** Writes the card into the page's tags. Every value is escaped; the page's own markup is left alone. */
function phonetv_share_fill($html, $card, $url) {
    $title = esc_html($card['title']);
    $html  = preg_replace('#<title>.*?</title>#s', '<title>' . $title . '</title>', $html, 1);
    $tags  = array(
        'name="description"'             => $card['description'],
        'property="og:title"'            => $card['title'],
        'property="og:description"'      => $card['description'],
        'property="og:image"'            => $card['image'],
        'property="og:url"'              => $url,
        'name="twitter:title"'           => $card['title'],
        'name="twitter:description"'     => $card['description'],
        'name="twitter:image"'           => $card['image'],
    );
    foreach ($tags as $which => $value) {
        $html = preg_replace(
            '#<meta ' . preg_quote($which, '#') . ' content="[^"]*">#',
            '<meta ' . $which . ' content="' . esc_attr($value) . '">',
            $html,
            1
        );
    }
    return $html;
}
