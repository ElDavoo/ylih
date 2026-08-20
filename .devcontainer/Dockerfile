# nixos/nix isn't FHS-compliant (no /lib64/ld-linux, no standard userland), so the
# VS Code Server binary it downloads on attach can't bootstrap — that's the
# "failed to start vscode server" failure. A normal Debian base plus the
# Determinate Systems installer gives Code a real Linux environment to land in
# while still providing Nix for the actual build.
FROM mcr.microsoft.com/devcontainers/base:bookworm

RUN curl --proto '=https' --tlsv1.2 -sSf -L https://install.determinate.systems/nix \
    | sh -s -- install linux --init none --no-confirm
ENV PATH="/nix/var/nix/profiles/default/bin:${PATH}"
