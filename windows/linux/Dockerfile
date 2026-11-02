# Build environment for the Linux GUI (Wails needs cgo + WebKitGTK 4.1). Debian bookworm's glibc
# (2.36) keeps the binary compatible with current Ubuntu, Fedora, Arch and Asahi.
# Uses the Huawei Cloud mirror directly (fast from China); apt still verifies Debian's signatures.
FROM golang:1.25-bookworm
RUN sed -i 's|http://deb.debian.org|http://mirrors.huaweicloud.com|g' /etc/apt/sources.list.d/*.sources \
 && unset http_proxy https_proxy HTTP_PROXY HTTPS_PROXY \
 && O='-o Acquire::http::Proxy=false -o Acquire::https::Proxy=false -o Acquire::Retries=5' \
 && apt-get $O update \
 && apt-get $O install -y --no-install-recommends libgtk-3-dev libwebkit2gtk-4.1-dev pkg-config build-essential \
 && rm -rf /var/lib/apt/lists/*
WORKDIR /src
