package com.forjastudios.skinstudio;

public enum BodyPart {
    HEAD("Cabeza", "Cabeza completa", new int[][]{{8,0,16,8},{0,8,32,16}}),
    HAIR("Pelo", "Pinta la zona superior y frontal del cabello", new int[][]{{8,0,16,3},{0,8,32,11}}),
    FACE("Cara", "Pinta la cara en la zona frontal de la cabeza", new int[][]{{8,8,16,16}}),
    CHEST("Pecho", "Pinta el torso completo", new int[][]{{20,16,28,20},{16,20,40,32},{20,32,28,36}}),
    ARMS("Brazos", "Pinta las dos mangas y brazos", new int[][]{{44,16,48,20},{40,20,56,32},{44,32,48,36},{36,48,40,52},{32,52,48,64},{36,64,40,64}}),
    HANDS("Manos", "Pinta las zonas inferiores de ambos brazos", new int[][]{{40,28,44,32},{32,60,36,64}}),
    LEGS("Piernas", "Pinta las dos piernas completas", new int[][]{{4,16,8,20},{0,20,16,32},{4,32,8,36},{4,48,8,52},{0,52,16,64}}),
    SHOES("Zapatos", "Pinta la parte inferior visible de las piernas", new int[][]{{4,28,8,32},{4,60,8,64}});

    private final String label;
    private final String description;
    private final int[][] regions;
    BodyPart(String label,String description,int[][] regions){this.label=label;this.description=description;this.regions=regions;}
    public String getLabel(){return label;}
    public String getDescription(){return description;}
    public String getGroup(){
        switch(this){
            case HEAD: case HAIR: case FACE:return "HEAD";
            case CHEST:return "TORSO";
            case ARMS: case HANDS:return "ARMS";
            default:return "LEGS";
        }
    }
    public int[][] getRegions(SkinType type){
        int[][] copy=new int[regions.length][4];
        for(int i=0;i<regions.length;i++) System.arraycopy(regions[i],0,copy[i],0,4);
        return copy;
    }
}