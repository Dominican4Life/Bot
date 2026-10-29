# Notes for AI sessions working on this fork

Before changing code, read **[docs/fork-guide/README.md](docs/fork-guide/README.md)** and the section for the area you're touching. It explains how the fork's features work, why they were built that way, what must not change, and what breaks what. Also follow [AGENTS.md](AGENTS.md).

- Explain the plan to the owner before building: every push of code starts a CI build and a GitHub release (rN).
- Pushing only `.md` files does not start a build (`paths-ignore: '**.md'`).
- After a change ships, update the matching section of the guide (what changed, why, which release).
