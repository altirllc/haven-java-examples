package haven.loom.domain;

/** Pricing plans. `free` refuses new work at the allowance; `paid` continues and the excess is metered. */
public enum Plan {
    FREE("free"),
    PAID("paid");

    private final String wire;

    Plan(String wire) {
        this.wire = wire;
    }

    @Override
    public String toString() {
        return wire;
    }
}
