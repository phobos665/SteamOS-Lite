# Follow-ups

Work that is agreed but not scheduled yet.

## GOG / Epic: choosing which DLC to install

Today every owned DLC installs with its game: GOG installs every owned depot in the build, Epic
downloads each owned DLC's manifest after the game's. Nothing lets the user leave one out.

What to add:

- **Game page:** a checklist of the owned DLC, all ticked by default, shown before Install and on
  Update. The choice is kept per game, so Update keeps honouring it.
- **Download request:** `StoreDownloadService.enqueue` takes the chosen DLC ids and passes them to
  the downloaders.
  - `GOGDownloader` filters `depots` to the base product plus the chosen DLC product ids (Gen1 and
    Gen2 alike).
  - `EpicDownloader` plans only the chosen entries of `game.dlc`.
- **Removing DLC that is already installed:** its files have to go.
  - Epic: each DLC's manifest lists its files, so they can be deleted directly.
  - GOG: delete the files of that DLC's depots, except any path a remaining depot also lists.
  - `Installation.dlc` becomes the list of installed DLC ids, not titles.
- **Size:** the page shows the download size with the chosen set, from the depot sizes (GOG) or
  each manifest's chunk sizes (Epic).

Out of scope: Epic games that need the EA app or Ubisoft Connect, and cloud saves.
