# Notes for coding agents

This file is the same in SpacePixels, JTransient and JPlateSolve, and on every machine.

1. **Read [DEVELOPER.md](DEVELOPER.md) first.** It holds the maintainer's principles, the working rules (settings, commits, real-data checks), how to build and test, and how the code is organised. Follow it.
2. **Read `LOCAL-ENVIRONMENT.txt`** in the repository root, if it exists. It holds this machine's paths: tools, test datasets, scratch folders and memory limits. It is git-ignored and differs per machine. Never commit it, and never copy its paths into tracked files, commit messages or published reports. If it is missing and you need a path, ask the maintainer.
3. **Pending work** is in `DESIGN-IMPROVEMENTS.md` (design and refactoring) and `FEATURE-REQUESTS.md` (functionality). An item there is a proposal, not an instruction to implement it; discuss before changing code, unless the maintainer has decided it.
4. **The three repositories are developed together.** SpacePixels is the application; JTransient (detection engine) and JPlateSolve (plate solving) are its libraries, usually checked out side by side. A library change updates its SpacePixels callers in the same piece of work.

Working habits:

- Keep diagnostic programs, test outputs and harnesses in a scratch folder outside the repositories.
- Stage only your own changes; leave unrelated working-tree edits alone.
- When you are unsure whether something is wanted, ask rather than guess.
