## Collaboration

**Read [`.agents/skills/collaboration/SKILL.md`](.agents/skills/collaboration/SKILL.md) and follow it.** It is the authoritative description of how to work with the repository owner: when to ask instead of acting, how to make commands reviewable before requesting approval, the checkpointed rhythm for working an issue, and what to report. It is written to be tool-agnostic — do not duplicate its rules here, so the two cannot drift apart.

One project-specific instance of its "don't discard a clean solution because it is awkward for you" rule: editing `project.pbxproj` directly is undesirable for an agent, but that does *not* rule out solutions needing project changes. The owner can trivially create a file in the right group and set target membership (e.g. S-pushTAN or BW-pushTAN). Propose that when it is the cleanest path, and ask them to do that step.

### Always stop and ask first

The skill's rules ask for judgement, and judgement decays over a long session — agreeing early is not a licence to decide alone later. These triggers need none. Each one means: describe what you intend, then wait.

- **Shared resources.** `.gitignore`, or CI configuration.
- **Breadth.** A change spanning more than ~10 files, or adding more than ~10 entries to any shared file.
- **New patterns at scale.** Applying an existing convention far beyond its current footprint (e.g. a technique used in 6 places, applied to 40) is a *new* decision, not the old one continued.
- **Anything irreversible or outward-facing.** Deleting or re-recording reference material, re-ordering a generated file, touching the web repository.
- **Deviating from an agreed plan**, even when the plan looks wrong. Say so and wait; do not implement the better idea and report it afterwards.

Asking once about a topic does not license every later decision in the same area. If you cannot point to the message where the owner agreed to *this* change, it has not been agreed.

## Core instructions
- Do not introduce third-party frameworks without asking first.
- Ask comprehensive questions, if different solutions are available.
- Start out with a plan, which all agree on, before making any changes.

## Project structure

- Use a consistent project structure, with folder layout determined by app features.
- Follow strict naming conventions for types, properties, methods and models.
- Prefer self explaining variable and function names, but add code comments and documentation comments for key code structures or unusual workarounds or hard to reason code logic. The code + comments shall be self-explaining.
- If the project requires secrets such as API keys, never include them in the repository, ask what to do with them.
