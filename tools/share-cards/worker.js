/**
 * Share cards for Phone TV.
 *
 * Facebook, X, WhatsApp and the rest build a link's preview card by reading the page's
 * <meta> tags, and they don't run scripts. Phone TV's share page lives on GitHub Pages,
 * which serves the same file for every link, so every share would get the same card.
 *
 * This small Cloudflare Worker sits in front of that page. For each link it fills in the
 * card's title, description and picture for the thing being shared (the poster of a film,
 * an actor's face, a studio's logo), then hands on the very same page. People see no
 * difference; crawlers see a card.
 *
 * It keeps nothing and knows nothing about who shares or opens a link.
 */

const PAGE = 'https://zgenmedia.github.io/phone-tv/s/index.html';
const DEFAULT_IMAGE = 'https://zgenmedia.github.io/phone-tv/assets/og.jpg';
const IMAGE_HOSTS = ['commons.wikimedia.org', 'upload.wikimedia.org', 'static.tvmaze.com', 'images.metahub.space'];

export default {
  async fetch(request) {
    const url = new URL(request.url);
    const subject = read(url.searchParams);
    const page = await fetch(PAGE, { cf: { cacheTtl: 600, cacheEverything: true } });
    if (!subject) return new Response(page.body, page); // an incomplete link: the page says so itself

    const card = await describe(subject);
    const set = (value) => ({ element(e) { e.setAttribute('content', value); } });
    return new HTMLRewriter()
      .on('title', { element(e) { e.setInnerContent(card.title); } })
      .on('meta[name="description"]', set(card.description))
      .on('meta[property="og:title"]', set(card.title))
      .on('meta[property="og:description"]', set(card.description))
      .on('meta[property="og:image"]', set(card.image))
      .on('meta[property="og:url"]', set(url.toString()))
      .on('meta[name="twitter:title"]', set(card.title))
      .on('meta[name="twitter:description"]', set(card.description))
      .on('meta[name="twitter:image"]', set(card.image))
      .transform(new Response(page.body, {
        status: 200,
        headers: { 'content-type': 'text/html; charset=utf-8', 'cache-control': 'public, max-age=600' },
      }));
  },
};

/** The same checks the page and the app make: a link names a subject and nothing else. */
function read(q) {
  const type = q.get('t') || '', id = q.get('id') || '', name = (q.get('n') || '').slice(0, 120);
  const ok = (type === 'movie' || type === 'series') ? /^tt\d{5,10}$/.test(id)
    : type === 'company' ? /^Q\d{1,10}$/.test(id) && !!name
    : type === 'person' ? (id === '' ? !!name : /^Q\d{1,10}$/.test(id))
    : false;
  if (!ok) return null;
  let image = '';
  try {
    const sent = new URL(q.get('i') || '');
    if (sent.protocol === 'https:' && IMAGE_HOSTS.includes(sent.hostname)) image = sent.toString();
  } catch (e) { /* no picture sent */ }
  return { type, id, name, image, network: q.get('k') === 'network' };
}

async function describe(s) {
  if (s.type === 'movie' || s.type === 'series') {
    const card = {
      title: `${s.name || 'Something to watch'} · on Phone TV`,
      description: `${s.type === 'movie' ? 'Movie' : 'Show'} shared from Phone TV, the free TV home screen for phones, TV boxes, PCs and Macs.`,
      image: `https://images.metahub.space/background/medium/${s.id}/img`,
    };
    try {
      // The open catalog's own line about the title makes a better card than ours.
      const r = await fetch(`https://v3-cinemeta.strem.io/meta/${s.type}/${s.id}.json`, { cf: { cacheTtl: 86400, cacheEverything: true } });
      const meta = (await r.json()).meta || {};
      if (meta.name) card.title = `${meta.name}${meta.releaseInfo ? ` (${meta.releaseInfo})` : ''} · on Phone TV`;
      if (meta.description) card.description = meta.description.length > 190 ? meta.description.slice(0, 187) + '…' : meta.description;
    } catch (e) { /* the plain card stands */ }
    return card;
  }
  if (s.type === 'person') {
    return {
      title: `${s.name} · on Phone TV`,
      description: `Everything ${s.name} has been in, in one place. Shared from Phone TV, the free TV home screen.`,
      image: s.image || DEFAULT_IMAGE,
    };
  }
  return {
    title: `${s.name} · on Phone TV`,
    description: `Everything from ${s.name}${s.network ? ', the network' : ''}, in one place. Shared from Phone TV, the free TV home screen.`,
    image: s.image || DEFAULT_IMAGE,
  };
}
