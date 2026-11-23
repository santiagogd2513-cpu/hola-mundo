# Build pipeline

The libertyuv-android branch runs the GitHub Actions workflow at .github/workflows/build.yml on every push. It runs JVM tests, assembles the debug APK, uploads the APK as a workflow artifact, and publishes it as a GitHub Release for push builds.
