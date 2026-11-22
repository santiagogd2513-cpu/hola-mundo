package com.forjastudios.skinstudio;

public enum BodyPart {
    HEAD("Cabeza", "Pinta las seis caras de la cabeza y su capa exterior", new int[][]{
        {8,0,24,8}, {0,8,32,16}, {40,0,56,16}
    }),
    HAIR("Pelo", "Pinta únicamente la capa exterior del cabello", new int[][]{
        {40,0,56,16}
    }),
    FACE("Cara", "Pinta la cara frontal de la cabeza", new int[][]{
        {8,8,16,16}
    }),
    CHEST("Pecho", "Pinta todas las caras del torso y su chaqueta exterior", new int[][]{
        {20,16,36,20}, {16,20,40,32}, {16,32,40,48}
    }),
    ARMS("Brazos", "Pinta ambos brazos completos, incluidas sus mangas exteriores", new int[][]{
        {40,16,56,32}, {40,32,56,48}, {32,48,48,64}, {48,48,64,64}
    }),
    HANDS("Manos", "Pinta los extremos inferiores de ambos brazos", new int[][]{
        {40,28,56,32}, {40,44,56,48}, {32,60,48,64}, {48,60,64,64}
    }),
    LEGS("Piernas", "Pinta ambas piernas y sus capas exteriores", new int[][]{
        {0,16,16,32}, {0,32,16,48}, {16,48,32,64}, {0,48,16,64}
    }),
    SHOES("Zapatos", "Pinta las zonas inferiores visibles de las piernas", new int[][]{
        {0,28,16,32}, {0,44,16,48}, {16,60,32,64}, {0,60,16,64}
    });

    private final String label;
    private final String description;
    private final int[][] regions;

    BodyPart(String label, String description, int[][] regions) {
        this.label = label;
        this.description = description;
        this.regions = regions;
    }

    public String getLabel() { return label; }
    public String getDescription() { return description; }

    public String getGroup() {
        switch (this) {
            case HEAD: case HAIR: case FACE: return "HEAD";
            case CHEST: return "TORSO";
            case ARMS: case HANDS: return "ARMS";
            default: return "LEGS";
        }
    }

    public int[][] getRegions(SkinType type) {
        int[][] copy = new int[regions.length][4];
        for (int i = 0; i < regions.length; i++) {
            System.arraycopy(regions[i], 0, copy[i], 0, 4);
        }
        return copy;
    }
}