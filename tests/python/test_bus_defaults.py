"""What a freshly placed bus allows, before anyone configures it.

Written because a real client showed a brand-new export bus already allowing ten items -- every log in the
game -- when the whole point of its default is that it allows nothing and is therefore inert. Either the
construction is wrong or "allows nothing" is not what vanilla's allowAll=false means.
"""

from __future__ import annotations


def test_what_a_fresh_export_bus_allows(storage):
    storage.place("terminal", 0, 0)
    storage.place("unit", 1, 0)
    storage.place("exportbus", 2, 0)

    state = storage.query("busfilter", 2, 0, "ironbar")

    print("\nfresh export bus:", state)
    assert state["allowedcount"] == 0, f"expected an inert bus, it allows {state['allowedlist']}"


def test_what_a_fresh_import_bus_allows(storage):
    storage.place("terminal", 0, 0)
    storage.place("unit", 1, 0)
    storage.place("importbus", 2, 0)

    state = storage.query("busfilter", 2, 0, "ironbar")

    print("\nfresh import bus:", state["allowedcount"], state["allowedlist"])
    assert state["allowedcount"] > 100, "an import bus should allow essentially everything"


def test_a_copy_of_the_filter_keeps_everything_it_allowed(storage):
    """The count, not one item. An import bus allows everything, so a lossy copy is obvious here and was
    invisible to a test that only ever asked about iron bars."""
    storage.place("terminal", 0, 0)
    storage.place("unit", 1, 0)
    storage.place("importbus", 2, 0)
    before = storage.query("busfilter", 2, 0, "ironbar")["allowedcount"]

    storage.do("busroundtrip", 2, 0, "ironbar", 25)

    after = storage.query("busfilter", 2, 0, "ironbar")["allowedcount"]
    assert after == before, f"the copy lost {before - after} of {before} allowed items"
