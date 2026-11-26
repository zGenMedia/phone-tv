<?php
/**
 * Plugin Name: Phone TV Share Cards
 * Description: Gives every Phone TV share link its own preview card (title, picture, a line about it) on Facebook, X and messaging apps. Serves the share page at /tv/.
 * Version: 1.1.0
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
 * It also keeps the iPhone waiting list. Phone TV's "coming to iPhone" page posts an email
 * address to /tv/notify; the address and the date go into one small table, and
 * Tools › Phone TV iPhone list shows how many people are waiting.
 *
 * Nothing else on the site is touched. Nothing is stored about who shares or opens a link.
 */

if (!defined('ABSPATH')) {
    exit;
}

const PHONETV_SHARE_PATH  = 'tv';
const PHONETV_SHARE_PAGE  = 'https://zgenmedia.github.io/phone-tv/s/index.html';
const PHONETV_SHARE_IMAGE = 'https://zgenmedia.github.io/phone-tv/assets/og.jpg';
const PHONETV_LIST_GOAL   = 1000; // how many people asking makes the iPhone version worth building

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
    if ($path === PHONETV_SHARE_PATH . '/notify') {
        phonetv_list_answer();
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


/* ---------------------------------------------------------------------------------------
 * The iPhone waiting list
 * ------------------------------------------------------------------------------------- */

function phonetv_list_table() {
    global $wpdb;
    return $wpdb->prefix . 'phonetv_iphone_list';
}

/** Makes the table the first time it is needed (replacing the plugin's files doesn't run an install step). */
function phonetv_list_ready() {
    if (get_option('phonetv_list_db') === '1') {
        return;
    }
    global $wpdb;
    require_once ABSPATH . 'wp-admin/includes/upgrade.php';
    dbDelta('CREATE TABLE ' . phonetv_list_table() . " (
        id bigint(20) unsigned NOT NULL AUTO_INCREMENT,
        email varchar(190) NOT NULL,
        joined datetime NOT NULL,
        PRIMARY KEY  (id),
        UNIQUE KEY email (email)
    ) " . $wpdb->get_charset_collate() . ';');
    update_option('phonetv_list_db', '1', false);
}

/** The pages allowed to post to the list: Phone TV's own site, and this one. */
function phonetv_list_origins() {
    $here = wp_parse_url(home_url());
    return array('https://zgenmedia.github.io', $here['scheme'] . '://' . $here['host']);
}

/** Answers /tv/notify: add an address, or take one off. Always replies in JSON and never says whether an address was already there. */
function phonetv_list_answer() {
    $origin = isset($_SERVER['HTTP_ORIGIN']) ? (string) $_SERVER['HTTP_ORIGIN'] : '';
    if (in_array($origin, phonetv_list_origins(), true)) {
        header('Access-Control-Allow-Origin: ' . $origin);
        header('Vary: Origin');
    }
    nocache_headers();
    $method = isset($_SERVER['REQUEST_METHOD']) ? $_SERVER['REQUEST_METHOD'] : 'GET';
    if ($method === 'OPTIONS') {
        header('Access-Control-Allow-Methods: POST');
        status_header(204);
        exit;
    }
    if ($method !== 'POST') {
        wp_send_json(array('ok' => false, 'why' => 'post'), 405);
    }

    $post  = wp_unslash($_POST); // phpcs:ignore -- a public form; everything is validated below
    $email = isset($post['email']) ? sanitize_email(trim((string) $post['email'])) : '';
    $leave = isset($post['leave']) && $post['leave'] === '1';

    // A field people never see. Whatever fills it in is not a person; it gets a cheerful yes and nothing is kept.
    if (!empty($post['website'])) {
        wp_send_json(array('ok' => true));
    }
    if ($email === '' || !is_email($email) || strlen($email) > 190) {
        wp_send_json(array('ok' => false, 'why' => 'email'), 400);
    }

    // A handful of tries an hour from one address is plenty. Only a scrambled form of it is kept, and only for the hour.
    $who   = 'phonetv_list_' . md5(wp_salt() . (isset($_SERVER['REMOTE_ADDR']) ? $_SERVER['REMOTE_ADDR'] : ''));
    $tries = (int) get_transient($who);
    if ($tries >= 8) {
        wp_send_json(array('ok' => false, 'why' => 'slow'), 429);
    }
    set_transient($who, $tries + 1, HOUR_IN_SECONDS);

    global $wpdb;
    phonetv_list_ready();
    if ($leave) {
        $wpdb->delete(phonetv_list_table(), array('email' => strtolower($email)), array('%s'));
    } else {
        // INSERT IGNORE: signing up twice is not an error and doesn't count twice.
        $wpdb->query($wpdb->prepare(
            'INSERT IGNORE INTO ' . phonetv_list_table() . ' (email, joined) VALUES (%s, %s)',
            strtolower($email),
            current_time('mysql', true)
        ));
    }
    wp_send_json(array('ok' => true));
}

add_action('admin_menu', 'phonetv_list_menu');
add_action('admin_post_phonetv_list_csv', 'phonetv_list_csv');

function phonetv_list_menu() {
    add_management_page('Phone TV iPhone list', 'Phone TV iPhone list', 'manage_options', 'phonetv-iphone-list', 'phonetv_list_screen');
}

/** Tools › Phone TV iPhone list: how many are waiting, the latest few, and the whole list as a file. */
function phonetv_list_screen() {
    if (!current_user_can('manage_options')) {
        return;
    }
    global $wpdb;
    phonetv_list_ready();
    $table  = phonetv_list_table();
    $count  = (int) $wpdb->get_var("SELECT COUNT(*) FROM $table"); // phpcs:ignore -- table name is ours
    $latest = $wpdb->get_results("SELECT email, joined FROM $table ORDER BY id DESC LIMIT 25"); // phpcs:ignore
    $pct    = min(100, (int) round($count * 100 / PHONETV_LIST_GOAL));
    $file   = wp_nonce_url(admin_url('admin-post.php?action=phonetv_list_csv'), 'phonetv_list_csv');

    echo '<div class="wrap"><h1>Phone TV iPhone list</h1>';
    echo '<p style="font-size:15px">People who asked to hear when Phone TV comes to iPhone.</p>';
    echo '<p style="font-size:40px;font-weight:700;margin:18px 0 6px">' . esc_html(number_format_i18n($count))
        . ' <span style="font-size:15px;font-weight:400;color:#646970">of ' . esc_html(number_format_i18n(PHONETV_LIST_GOAL)) . '</span></p>';
    echo '<div style="max-width:420px;height:10px;background:#dcdcde;border-radius:5px;overflow:hidden"><div style="height:10px;width:'
        . esc_attr($pct) . '%;background:' . ($count >= PHONETV_LIST_GOAL ? '#00a32a' : '#2271b1') . '"></div></div>';
    if ($count >= PHONETV_LIST_GOAL) {
        echo '<p><strong>The goal has been reached.</strong></p>';
    }
    echo '<p style="margin-top:18px"><a class="button button-primary" href="' . esc_url($file) . '">Download the list (CSV)</a></p>';
    if ($latest) {
        echo '<h2>Latest</h2><table class="widefat striped" style="max-width:560px"><thead><tr><th>Email</th><th>Joined (UTC)</th></tr></thead><tbody>';
        foreach ($latest as $row) {
            echo '<tr><td>' . esc_html($row->email) . '</td><td>' . esc_html($row->joined) . '</td></tr>';
        }
        echo '</tbody></table>';
    }
    echo '</div>';
}

function phonetv_list_csv() {
    if (!current_user_can('manage_options')) {
        wp_die('Not allowed.');
    }
    check_admin_referer('phonetv_list_csv');
    global $wpdb;
    phonetv_list_ready();
    $rows = $wpdb->get_results('SELECT email, joined FROM ' . phonetv_list_table() . ' ORDER BY id', ARRAY_N); // phpcs:ignore
    nocache_headers();
    header('Content-Type: text/csv; charset=utf-8');
    header('Content-Disposition: attachment; filename="phone-tv-iphone-list.csv"');
    $out = fopen('php://output', 'w');
    fputcsv($out, array('email', 'joined_utc'));
    foreach ($rows as $row) {
        // An address can't start a spreadsheet formula, but be safe with anything that slipped through.
        $row[0] = ltrim($row[0], "=+-@\t\r");
        fputcsv($out, $row);
    }
    fclose($out);
    exit;
}
