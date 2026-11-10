# Archipelago core, built from a pinned upstream release.
# The same image is used for seed generation (locally) and for the game server.
FROM python:3.12-slim-bookworm

ARG AP_VERSION=0.6.8
ENV PYTHONUNBUFFERED=1

# hadolint ignore=DL3008
RUN apt-get update \
 && apt-get install -y --no-install-recommends git gcc g++ libc6-dev \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /ap
RUN git clone --depth 1 --branch "${AP_VERSION}" https://github.com/ArchipelagoMW/Archipelago.git . \
 && rm -rf .git

# Core requirements + compiled speedups (otherwise AP tries to build them at runtime).
RUN pip install --no-cache-dir -r requirements.txt cython "setuptools>=75,<81" \
 && cythonize -b -i _speedups.pyx

# Install the requirements of every bundled world so generation works for all games.
RUN python ModuleUpdate.py -y

# Never try to pip-install anything at runtime.
ENV SKIP_REQUIREMENTS_UPDATE=true

COPY entrypoint.sh /usr/local/bin/ap-entrypoint
COPY room_info.py serve.py /ap/
RUN chmod +x /usr/local/bin/ap-entrypoint

ENTRYPOINT ["ap-entrypoint"]
CMD ["server"]
