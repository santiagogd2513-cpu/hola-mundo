# BD Acustica Ultra Complemento (Minecraft Forge 1.20.1)

Mod de físicas acústicas avanzadas para Beautiful Decor v7.39.3.

Autor: Santiago (Forja Studios)

## GitHub Actions

El workflow `.github/workflows/build.yml` compila automáticamente el mod con Gradle + ForgeGradle en cada push a `main` o pull request.

El artefacto JAR se sube como artifact de la Actions run.

## Cómo construir localmente

```bash
gradle build
```

Requiere Java 17.

## Nota

Se subieron los archivos de configuración y la mayoría del código fuente. Los archivos grandes del motor acústico (`AcousticRaytracer.java` y `SoundPhysicsHandler.java`) + iconos se pueden añadir manualmente desde el ZIP original para completar el build.
