# Releasing OutbackCDX

1. Prepare release notes in [CHANGELOG.md](CHANGELOG.md)
2. Prepare maven release `mvn release:prepare`
3. Perform maven release `mvn -B release:perform -Dgoals="clean verify"`
4. Build docker images:
```bash
version=1.0.1
podman manifest create nlagovau/outbackcdx:$version
podman build --build-arg version=$version --platform linux/amd64,linux/arm64 --manifest nlagovau/outbackcdx:$version docker
podman manifest push --all nlagovau/outbackcdx:$version
podman manifest push --all nlagovau/outbackcdx:$version nlagovau/outbackcdx:latest
```
5. Copy release notes from [CHANGELOG.md](CHANGELOG.md) into [Github release](https://github.com/internetarchive/heritrix3/releases)