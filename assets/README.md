# assets/

Presentation art for the repository itself — not shipped in the mod jar.

`banner.png` is the README header, 1024×512.

Art that the *game* loads lives in `src/main/resources/` instead, and its paths are dictated by
the engine rather than chosen: `objects/<stringID>.png`, `items/<stringID>.png`, and
`preview.png` at the root of the resources tree. `preview.png` in particular cannot be moved
here — the game reads that exact path for the in-game mod info panel, where it is drawn 128px
tall — so it is the same banner image kept where the engine requires it.
