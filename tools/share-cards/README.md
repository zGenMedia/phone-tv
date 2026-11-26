# Share cards

`worker.js` is a Cloudflare Worker that gives every Phone TV share link its own preview
card on Facebook, X, WhatsApp, iMessage and the like. The comment at the top of the file
explains why it is needed (in short: those sites don't run scripts, and GitHub Pages
can't change a page per link).

## Putting it live

1. Cloudflare dashboard › Workers & Pages › Create › Worker. Name it `phonetv-share`.
2. Paste `worker.js` over the sample code and Deploy.
3. Give it an address. Either keep the `…workers.dev` one Cloudflare assigns, or under
   Settings › Domains & Routes add a custom domain such as `tv.zgenmedia.com`.
4. Set `Share.PAGE` in the app (`Share.java`) to that address followed by `/s/`, and add
   the host to the share intent-filter in `AndroidManifest.xml`. Links made by older
   versions keep working: they still open the page on GitHub Pages, just without a card.

The free Workers plan allows 100,000 requests a day.

## Checking a card

Paste a link into https://developers.facebook.com/tools/debug/ or post it in a private
chat. The card should show the title, a line about it and its picture.
