# Trademarks and branding

The source code of Carrier Pigeon is licensed under the [Apache License 2.0](LICENSE). That licence covers the code, not the project's name or its pigeon icon.

## What isn't covered by the Apache License

- **The name "Carrier Pigeon"** as the name of this app, its widget and its store listings. Section 6 of the Apache License says the licence "does not grant permission to use the trade names, trademarks, service marks, or product names of the Licensor", except as needed to describe where a work comes from.
- **The pigeon icon and logo artwork.** These files are excluded from the Apache License and stay all rights reserved:

  | File | What it is |
  | --- | --- |
  | `app/src/main/res/drawable/ic_launcher_foreground.xml` | The pigeon (launcher icon foreground layer) |
  | `app/src/main/res/drawable/ic_launcher_background.xml` | The evening-sky background behind it |
  | `app/src/main/res/drawable/ic_launcher_monochrome.xml` | The single-colour pigeon for themed icons |
  | `docs/icon-preview.png` | A rendered preview of the icon |

  `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` only wires these layers together, so it stays under the Apache License. Unchanged copies of the project's own builds may of course show the icon.

## Forks and modified versions

You're welcome to fork the code under the Apache License. If you distribute a modified version, please:

1. **Rename it.** Don't call it "Carrier Pigeon" or anything confusingly similar. Change `app_name`, `home_title` and the other strings in `app/src/main/res/values*/` that mention the name, and the store listing text.
2. **Use your own icon.** Replace every file in the table above with your own artwork.
3. **Use your own package name.** Change `applicationId` and `namespace` in `app/build.gradle.kts`, so your app can't be mistaken for, or installed over, this one.

You may still say your app is "based on Carrier Pigeon", with a link to this repository. That describes where the code comes from, which Section 6 allows.

## Questions

If you're unsure whether a use is fine, open an issue on this repository and ask.
