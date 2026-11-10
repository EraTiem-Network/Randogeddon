# World Locks

Add one slot of this game to the session and list the worlds that start locked:

```yaml
name: Locks
game: World Locks
World Locks:
  locked_worlds: ["Alice-Celeste", "Bob-Yacht"]
  early_keys: false   # true: keys are reachable from the start, so locked worlds open early
```

Each locked world gets a key ("World Key 1", "World Key 2", ... in the order of the names, sorted) that is placed
somewhere else in the multiworld. The seed is generated so that each locked world is only needed after its key is
found. Nobody plays the Locks slot; the dashboard shows which worlds are locked and when their key turns up.
