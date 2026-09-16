package haven.plumb.probe;

/** Page sections, rendered in declaration order. */
public enum ProbeGroup {
    IDENTITY("Identity"),
    DATA("Data"),
    PLATFORM("Platform"),
    SIBLINGS("Sibling apps"),
    SELF("Self");

    private final String title;

    ProbeGroup(String title) {
        this.title = title;
    }

    public String title() {
        return title;
    }
}
