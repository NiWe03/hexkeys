# Contributing

Bug reports, ideas and pull requests are welcome.

- **Bugs:** open an issue with your phone, Android version, the hexkeys version you installed and what you did, what you expected and what happened instead. For gesture problems a short screen recording helps most.
- **Code:** the core is in `src/` (Rust), the Android shell in `android/` (Kotlin). Run `cargo test --workspace` before sending a change; the CI runs it too. Keep each pull request to one change.
- **Languages:** dictionaries come from [Helium314/aosp-dictionaries](https://codeberg.org/Helium314/aosp-dictionaries); a missing or poor dictionary is best fixed there.

By contributing you agree that your work is published under the project's license, the GPL-3.0.
