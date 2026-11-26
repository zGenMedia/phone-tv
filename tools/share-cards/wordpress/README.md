# Share cards on a WordPress site

`phone-tv-share-cards` is a small WordPress plugin. Once active, the site answers
`https://yoursite.com/tv/?t=…&id=…&n=…` with Phone TV's share page, with the preview card
(title, picture, a line about it) filled in for whatever is being shared. That is what
Facebook, X and messaging apps read when a link is posted.

Install: zip the `phone-tv-share-cards` folder, then in WordPress go to
Plugins › Add New › Upload Plugin, choose the zip and activate it. There is nothing to set up.

It also keeps the iPhone waiting list: the "coming to iPhone" page posts an email address
to `/tv/notify`, and Tools › Phone TV iPhone list shows the count and offers the list as
a CSV. That is its one table (`wp_phonetv_iphone_list`: email and date).

If the share page can't be reached it sends visitors on to
the original page on GitHub Pages, so a link never dead-ends.

If the site runs a page cache, exclude `/tv/` from it: every link is its own page.
