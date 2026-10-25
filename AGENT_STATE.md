# Agent State — design improvements collaboration

## Collaboration

- Goal: Implement all remaining DESIGN-IMPROVEMENTS.md items from SP-05 through SP-19, as authorized on 2026-10-25; exclude SP-21 and SP-22.
- Spec: [DESIGN-IMPROVEMENTS.md](DESIGN-IMPROVEMENTS.md), including each issue review; follow [DEVELOPER.md](DEVELOPER.md).
- Branch: `feature/design-improvements-collaboration`
- Feature baseline: `b4bac726b6242d029d9c7636cccffd25d8d85895`
- Agents (ordered): A=this session; B=maintainer-started Agent B; C=maintainer-started Agent C. No additional agents are needed.
- Overall: ACTIVE
- Execution: One shared working tree, one committed Owner; A implements / B reviews, B implements / C reviews, C implements / A reviews. Waiting agents are read-only.
- Scope boundary: Completed SP-10, SP-13 and SP-18 stay complete; dropped SP-15 stays dropped. SP-23 is already complete. No dependency upgrades or unrelated functional requests.
- Required final checks: `gradlew.bat check`, `guiMechanicsTest`, `apiConsumerTest`; reference-data before/after comparisons for preparation/report/numeric changes as in DEVELOPER.md. Large JVMs run sequentially, using LOCAL-ENVIRONMENT.txt and scratch homes. Run the relevant checks per task before review.
- Final integration: Maintainer approval is required before push/merge of this feature. Quota visibility: UNKNOWN.

## Task ledger

| ID | Task / spec | Depends on | Status | Evidence / deviations / next action |
| --- | --- | --- | --- | --- |
| SP-06 | Extract the sky-catalogue workflow | - | DONE | Reviewed `bd173bee9374516eddb947d327657cde4180c63a`. Reran SkyCatalogueControllerTest 9, ProcessingSessionTest 10, DependencyRulesTest 9: PASS. GUI mechanics not rerun (they move the mouse); implementer recorded SessionMechanicsTest and SessionIoMechanicsTest PASS. No code change in review. |
| SP-05 | Give panels specific collaborators and listeners | SP-06 | TODO | Preserve EDT notification, captured worker inputs and listener detachment. |
| SP-08 | Complete report request/context and consolidate entry points | - | TODO | Accept review cautions for valid absence, cancellation, atomic publication and explicit volatile-field normalization. |
| SP-07 | Fail-fast settings apply and direct row wiring | SP-05 | TODO | Use the reduced proposal; preserve explicit engine targets, separate reset lifecycles, invalid text and saved baselines. |
| SP-11A | Immutable frame metadata and session-owned WCS replacement | - | TODO | Retain array/container and header-map protection; preserve admitted run snapshots. |
| SP-11B | Clarify FITS input helpers and static naming | SP-11A | TODO | Consolidate only where responsibility/dependencies become clearer; keep converters separate. |
| SP-19A | Shared image component and small viewer helpers | SP-05 | TODO | Preserve real keyboard/focus behavior, cancellation and listener release; Blink stays separate by composition. |
| SP-19B | Viewer ownership and lifetime policy | SP-19A | TODO | Decision D-01 must be settled before changing independent viewer lifetime. |
| SP-19C | Shared trial-setting controls and edit/apply policy | SP-19A | TODO | Decision D-02; existing viewers only unless the maintainer explicitly includes F13. |
| SP-16 | Inspect/fix 100%, 150%, 200% scaling and larger font | SP-05, SP-07, SP-19A | TODO | Inspect empty/loaded window, settings, Astrometry Config, viewers and progress; retain real focus checks. |
| SP-14 | Move core code into domain packages | All other eligible in-scope work | TODO | Last mechanical step; decision D-03, source/resource patterns, API examples, release docs and consumer check. |

Statuses: TODO, IN_PROGRESS, REVIEW, DONE, DEFERRED. Independent review is required for DONE. Split SP-11 and SP-19 rows together cover their original items; do not remove the parent proposal before all its authorized parts are accounted for.

## Maintainer decisions

### D-01 — Independent viewer lifetime
- Task: SP-19B. OPEN: proposal closes every viewer on import; DEVELOPER.md deliberately permits independent inspection windows to retain the old session.
- Next action: Audit affected viewers and present the concrete workflow change for an explicit choice before implementation. Keep independent work moving.

### D-02 — Trial-setting policy and F13 boundary
- Task: SP-19C. OPEN: Preview Frame edits live settings; Star Mask Explorer uses Apply. The proposal ties consolidation to F13, which is not otherwise in this scope.
- Next action: Present a bounded common-control plan for the existing viewers and obtain the edit/apply choice. Do not silently implement F13.

### D-03 — Public package compatibility
- Task: SP-14. OPEN: moving exposed frame/run-option types changes public Java imports.
- Next action: Inventory the exact public type moves and propose an explicit compatibility/release decision before changing those packages. No compatibility shims without a real caller.
