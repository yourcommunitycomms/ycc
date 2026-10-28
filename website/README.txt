Your Community Communications website
====================

Plain static files, no build step and nothing loaded from other sites.
Upload the whole folder to any web host (GitHub Pages, Netlify, cPanel, S3, etc.).

  index.html          main page
  recreate.html       "Built with Claude" notes and the prompts to recreate the app
  codes.html          what DRYAD and pigpen are, and how to use them in the app (live pigpen demo)
  pi-board.html       flat Raspberry Pi expansion board: renders, photo slots, downloads
  style.css           shared styles (light and dark mode)
  img/pi-board-*.png  3D renders of the board, made with kicad-cli
  img/photos/         board photos: save board.jpg, board-headers.jpg, board-pi.jpg and
                      board-case.jpg here and they replace the placeholders automatically
  downloads/pi-board/ Gerbers and full KiCad designs for both board versions (copied from
                      Documents\KiCad, with local PC paths removed from the build scripts)
  img/logo.svg        logo (scalable); logo-512.png and logo-192.png for icons/social
  img/wordmark.svg    logo + name, for headers and print
  img/*.png           app screenshots from the test emulators
  downloads/your-community-communications.apk   current test build (debug-signed); copy app\build\outputs\apk\debug\app-debug.apk
                               over it after each new build so the site matches the features listed

Before publishing:
  - Replace downloads/your-community-communications.apk with a release build signed with your own key.
  - Check you are happy with the screenshots (they show test callsigns and channel tags only).
