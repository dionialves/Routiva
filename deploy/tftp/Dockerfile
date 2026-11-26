FROM debian:bookworm-slim

RUN apt-get update \
    && apt-get install -y --no-install-recommends tftpd-hpa \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 routiva \
    && useradd --uid 10001 --gid routiva --no-create-home routiva

COPY upload-only.rules /etc/tftp-upload-only.rules
ENTRYPOINT ["/usr/sbin/in.tftpd"]
