# Instructions given to the AI agent

The brief asks for any instruction or rules files given to an AI agent. This is the complete list.

## Rules files

None. No `CLAUDE.md`, `AGENTS.md`, `.cursorrules` or similar file was used. The agent was steered by the standing instructions given in
conversation, by its own memory notes and by the planning documents, all listed below.

## Persistent memory notes

The agent (Claude Code) keeps short memory notes between sessions. Two were written during this work. They are
reproduced here lightly edited: local paths and internal identifiers are removed.

**Commit style** (written on 21 November, at the start of the work)

> Commit in multiple small steps, each with a brief message saying what changed and why. Remember every conversation
> and decision made.
>
> Why: the assessment brief says git history is evidence (small commits, no squashing); the author also wants
> continuity across sessions.
> How to apply: one logical change per commit; keep a decision log and update the project note when major decisions
> are made.

**Project note** (kept up to date during the work)

> Take-home assessment for a senior software engineer role, with a deadline of Monday 23 November 2026. The brief is
> confidential. Never write the company name or the product name into the repository. Work in a new repository with a
> neutral name, on branch `main`, with a GitHub no-reply address as the commit author. Do not push or publish a
> release without the author's go-ahead. Record the open gaps honestly in the README.

## Standing instructions given in conversation

Given by the author in chat and followed for the whole work:

1. Make decisions section by section, in the order of the brief, and record each decision with the alternative that was
   rejected. Nothing is built before its decision is confirmed.
2. Work in small commits with brief messages, in the order of the confirmed commit plan; later problems are fixed by new
   commits, not by rewriting history. Each rule's mechanism and its test go in the same commit.
3. Keep one simple style (a single JDBC template style, one locking approach for the alert commands).
4. Keep the repository neutral: no company or product name in any file or in the history.
5. Do not ask for confirmation on routine steps once the plan is confirmed; stop and ask before anything outward-facing
   (pushing, publishing a release).

## Working documents

The agent also worked from planning documents written during the work: the design plan (every decision with its reason
and its rejected alternative), a list of patterns and principles, and the workflow of the live alert stream. The
design plan and the pattern list are kept outside the repository because they quote the brief; the stream workflow is
[live-alert-stream.md](live-alert-stream.md).
