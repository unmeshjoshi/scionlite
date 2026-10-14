package com.versioncontrol.scion.objects;

public enum ObjectType {
    BLOB("blob"), TREE("tree");

    private final String label;

    ObjectType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static ObjectType fromLabel(String label) {
        for (ObjectType type : values()) {
            if (type.label.equals(label)) {
                return type;
            }
        }
        throw new CorruptObjectException("unknown object type: " + label);
    }
}
