# Sample portrait assets

Eleven unique photographs from Unsplash, downloaded on 2026-10-21 and bundled as 900 × 1200 JPEGs. They are used under the [Unsplash License](https://unsplash.com/license), which allows downloading, copying, modifying and using images for free, including commercial use, without required attribution. This is a separate license from the application's 0BSD code license. Do not sell unmodified photographs or compile a competing photo service.

`sources.json` lists the exact image source URL, license reference, SHA-256 and byte size for each local file. The Unsplash website served a bot challenge when attempting to retrieve the live license page; the license reference is provided rather than a claimed locally verified copy of its terms. Images downloaded directly from Unsplash's image host over verified HTTPS.

These photographs illustrate a fictional prototype. Names, biographies, availability and rates are sample data and do not describe the photographed people. No model endorsement, real talent listing, professional portfolio, verification, follower count or review is asserted. The UI labels cards and profiles as samples, and gallery images as stock portrait studies featuring different models. Do not repurpose these prototype associations as real talent listings.

Static require() entries in `src/talentMedia.ts` ensure the photographs ship with Android, iOS and web builds. No image-host connection, token or image API is needed at runtime. Cropping happens through React Native Image `resizeMode="cover"`; source photographs remain unmodified in the checkout.
