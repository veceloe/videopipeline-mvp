FROM ghcr.io/ytsaurus/ui:stable-spyt
COPY secrets/yt-interface-secret.json /opt/app/secrets/yt-interface-secret.json
RUN chmod 600 /opt/app/secrets/yt-interface-secret.json