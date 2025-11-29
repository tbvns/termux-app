#!/bin/bash
set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

LOG_TAG="[Andronux-Bootstrap]"

echo -e "${BLUE}${LOG_TAG} Starting bootstrap installation...${NC}"

# Get home directory
HOME_DIR="/data/data/com.andronux.termux/files/home"
PROOT_DISTRO_DIR="$HOME_DIR/proot-distro"

# Step 1: Install proot-distro
echo -e "${YELLOW}${LOG_TAG} Installing proot-distro...${NC}"
if [ -f "$PROOT_DISTRO_DIR/install.sh" ]; then
    cd "$PROOT_DISTRO_DIR"
    bash install.sh
    cd "$HOME_DIR"
    echo -e "${GREEN}${LOG_TAG} proot-distro installed${NC}"
else
    echo -e "${RED}${LOG_TAG} proot-distro install.sh not found${NC}"
    exit 1
fi

# Step 2: Install Ubuntu
echo -e "${YELLOW}${LOG_TAG} Installing Ubuntu rootfs...${NC}"
proot-distro install ubuntu
echo -e "${GREEN}${LOG_TAG} Ubuntu installed${NC}"

# Step 3: Configure Ubuntu inside proot
echo -e "${YELLOW}${LOG_TAG} Configuring Ubuntu environment...${NC}"
# shellcheck disable=SC2016
proot-distro login ubuntu -- bash -c '
  set -e

  RED="\033[0;31m"
  GREEN="\033[0;32m"
  YELLOW="\033[1;33m"
  BLUE="\033[0;34m"
  NC="\033[0m"

  LOG_TAG="[Ubuntu-Setup]"

  # Detect architecture
  arch=$(uname -m)
  echo -e "${BLUE}${LOG_TAG} Architecture: $arch${NC}"

  case "$arch" in
    x86_64)
      HTTERM_ARCH="amd64"
      ;;
    aarch64)
      HTTERM_ARCH="arm64"
      ;;
    *)
      echo -e "${RED}${LOG_TAG} Unsupported architecture: $arch${NC}"
      exit 1
      ;;
  esac

  # 1. Install htterm
  echo -e "${YELLOW}${LOG_TAG} Installing htterm...${NC}"
  curl -fL -o /tmp/htterm https://github.com/tbvns/htterm/releases/download/1.0.0/htterm-${HTTERM_ARCH}
  mv /tmp/htterm /bin/htterm
  chmod +x /bin/htterm
  echo -e "${GREEN}${LOG_TAG} htterm installed${NC}"

  # 2. Update system and install dependencies
  echo -e "${YELLOW}${LOG_TAG} Updating system and installing dependencies...${NC}"
  export DEBIAN_FRONTEND=noninteractive
  apt-get update
  apt-get upgrade -y
  apt-get install -y \
    imagemagick \
    openjdk-21-jdk \
    build-essential \
    zip \
    git \
    sudo \
    unzip \
    curl \
    wget \
    xvfb \
    openbox \
    dolphin \
    firefox \
    chromium-browser \
    xterm \
    xdg-utils \
    busybox-static \
    tigervnc-standalone-server
  echo -e "${GREEN}${LOG_TAG} Dependencies installed${NC}"

  # 3. Install and setup busybox as shell
  echo -e "${YELLOW}${LOG_TAG} Setting up busybox as default shell...${NC}"
  busybox --install -s
  ln -sf /bin/busybox /bin/sh
  echo -e "${GREEN}${LOG_TAG} Busybox set as /bin/sh${NC}"

  # 4. Create non-root user
  echo -e "${YELLOW}${LOG_TAG} Creating andronux user...${NC}"
  useradd -m -s /bin/sh andronux || true
  echo "andronux:andronux" | chpasswd
  echo "andronux ALL=(ALL) NOPASSWD: ALL" > /etc/sudoers.d/andronux
  chmod 0440 /etc/sudoers.d/andronux
  echo -e "${GREEN}${LOG_TAG} User created${NC}"

  # 5. Download and install Android SDK tools
  echo -e "${YELLOW}${LOG_TAG} Installing Android SDK tools...${NC}"
  cd /tmp
  curl -fL -o tools.zip https://github.com/lzhiyong/android-sdk-tools/releases/download/35.0.2/android-sdk-tools-static-aarch64.zip
  unzip -q -o tools.zip

  if [ -d "build-tools" ]; then
    cp build-tools/* /usr/local/bin/
    chmod +x /usr/local/bin/aapt /usr/local/bin/aapt2 /usr/local/bin/zipalign 2>/dev/null || true
  fi

  if [ -d "platform-tools" ]; then
    cp platform-tools/* /usr/local/bin/
    chmod +x /usr/local/bin/adb /usr/local/bin/fastboot 2>/dev/null || true
  fi

  rm -rf tools.zip build-tools platform-tools others
  echo -e "${GREEN}${LOG_TAG} Android SDK tools installed${NC}"

  # 6. Install uber-apk-signer
  echo -e "${YELLOW}${LOG_TAG} Installing uber-apk-signer...${NC}"
  cd /tmp
  curl -fL -o uber-apk-signer.jar https://github.com/patrickfav/uber-apk-signer/releases/download/v1.3.0/uber-apk-signer-1.3.0.jar
  mv uber-apk-signer.jar /usr/local/bin/
  chmod +x /usr/local/bin/uber-apk-signer.jar
  echo -e "${GREEN}${LOG_TAG} uber-apk-signer installed${NC}"

  # 7. Download android.jar
  echo -e "${YELLOW}${LOG_TAG} Downloading android.jar...${NC}"
  mkdir -p /usr/local/lib
  cd /tmp
  curl -fL -o /usr/local/lib/android.jar https://raw.githubusercontent.com/Sable/android-platforms/master/android-34/android.jar
  echo -e "${GREEN}${LOG_TAG} android.jar installed${NC}"

  # 8. Configure Dolphin as default file manager
  echo -e "${YELLOW}${LOG_TAG} Configuring desktop environment...${NC}"
  xdg-mime default org.kde.dolphin.desktop inode/directory
  xdg-mime default org.kde.dolphin.desktop application/x-directory

  # 9. Create Openbox config
  mkdir -p /root/.config/openbox
  cat > /root/.config/openbox/rc.xml << '\''EOF'\''
<?xml version="1.0" encoding="UTF-8"?>
<openbox_config xmlns="http://openbox.org/3.4/rc" xmlns:xi="http://www.w3.org/2001/XInclude">
  <applications>
    <application class="*">
      <decor>yes</decor>
      <focus>yes</focus>
      <skip_pager>no</skip_pager>
      <skip_taskbar>no</skip_taskbar>
    </application>
  </applications>
</openbox_config>
EOF

  # 10. Create .xinitrc
  cat > /root/.xinitrc << '\''EOF'\''
#!/bin/sh
openbox &
dolphin &
exec tail -f /dev/null
EOF
  chmod +x /root/.xinitrc

  echo -e "${GREEN}${LOG_TAG} Desktop environment configured${NC}"

  # 11. Create termux.properties in andronux home
  echo -e "${YELLOW}${LOG_TAG} Creating termux.properties...${NC}"
  mkdir -p /home/andronux/.termux
  cat > /home/andronux/.termux/termux.properties << '\''EOF'\''
# Termux configuration
allow-external-apps = true
EOF
  chown -R andronux:andronux /home/andronux/.termux

  echo -e "${GREEN}${LOG_TAG} Ubuntu setup complete!${NC}"
'

echo -e "${GREEN}${LOG_TAG} Bootstrap installation completed successfully!${NC}"
echo -e "${YELLOW}${LOG_TAG} You can now use Andronux with Ubuntu environment${NC}"
