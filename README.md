# shootan-j2me

This is a silly j2me port of [shootan](https://github.com/InviseDivine/shootan) online game made by my friend.

# Progress

The game is now **playable** on a real device (tested on Nokia E65).
99% of game protocol is implemented.

## TODO
- [x] leaderboards
- [ ] rewrite font rendering
- [ ] local bluetooth multiplayer mode
- [ ] cute menus
- [x] chat
- [x] end round screen
- [x] pause menu
- [ ] code review and optimization
- [ ] maybe scale textures by 2 (because on 240x320 screen it looks so tiny)
- [ ] make space used more effectively in [spritesheet](./res/spritesheet.png)
- [ ] pspkvm support

<img src="./screenshot.png" width="48%">
<img src="./screenshot2.png" width="48%">
<img src="./screenshot3.png" width="48%">
<img src="./screenshot4.png" width="48%">

# Resources used
- [shootan](https://github.com/InviseDivine/shootan) - the base game
- [enet-j2me](https://github.com/Kolyah35/enet-j2me) - networking
- [compress-j2me](https://github.com/igorgatis/compress-j2me) - decompressing levels
- [rd-132211](https://github.com/thecodeofnotch/rd-132211) - the base game uses timer and AABB from this game
- [grixel-acme](https://www.dafont.com/es/grixel-acme-9.font) - font that raylib default font inspired on
- [tiny-font](https://www.ffonts.net/Sevastopol-Interface-Regular.font) - for player names and chat