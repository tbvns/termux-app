#!/bin/bash
set -e

# Color codes
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

echo -e "${BLUE}=== Starting proot-distro setup ===${NC}" >&2

cd proot-distro
echo -e "${YELLOW}Installing proot-distro...${NC}" >&2
./install.sh

echo -e "${YELLOW}Installing archlinux...${NC}" >&2
proot-distro install archlinux

# shellcheck disable=SC2016
proot-distro login archlinux -- bash -c '
  set -e

  RED="\033[0;31m"
  GREEN="\033[0;32m"
  YELLOW="\033[1;33m"
  BLUE="\033[0;34m"
  NC="\033[0m"

  arch=$(uname -m)
  echo -e "${BLUE}Architecture: $arch${NC}" >&2

  case "$arch" in
    x86_64)
      echo -e "${YELLOW}Grabbing htterm for amd64...${NC}" >&2
      curl -o htterm https://github.com/tbvns/htterm/releases/download/1.0.0/htterm-amd64
      ;;
    aarch64)
      echo -e "${YELLOW}Grabbing htterm for arm64...${NC}" >&2
      curl -o htterm https://github.com/tbvns/htterm/releases/download/1.0.0/htterm-arm64
      ;;
    *)
      echo -e "${RED}✗ Unsupported: $arch${NC}" >&2
      exit 1
      ;;
  esac

  mv ./htterm /bin/htterm
  chmod +x /bin/htterm
  echo -e "${GREEN}✓ htterm ready${NC}" >&2

  echo -e "${RED}Updating packages and installing dependencies${NC}" >&2
  pacman -Syu --noconfirm imagemagick jdk-openjdk base-devel zip git sudo unzip
  echo -e "${GREEN}✓ Updated${NC}" >&2

  echo -e "${YELLOW}Creating non-root andronux user...${NC}" >&2
  useradd -m -s /bin/sh andronux || true
  echo "andronux" | passwd andronux --stdin 2>/dev/null || echo "andronux:andronux" | chpasswd
  echo -e "${GREEN}✓ User andronux created${NC}" >&2

  echo -e "${YELLOW}Setting up sudoers for andronux (NOPASSWD)...${NC}" >&2
  echo "andronux ALL=(ALL) NOPASSWD: ALL" >> /etc/sudoers.d/andronux
  chmod 0440 /etc/sudoers.d/andronux
  echo -e "${GREEN}✓ Sudoers configured${NC}" >&2

  echo -e "${YELLOW}Installing yay (AUR helper) as andronux...${NC}" >&2
  cd /tmp
  sudo -u andronux git clone https://aur.archlinux.org/yay.git
  cd yay
  sudo -u andronux makepkg -si --noconfirm
  cd /
  rm -rf /tmp/yay
  echo -e "${GREEN}✓ yay installed${NC}" >&2

  echo -e "${YELLOW}Downloading ARM64 Android SDK tools...${NC}" >&2
  cd /tmp
  curl -fL https://github.com/lzhiyong/android-sdk-tools/releases/download/35.0.2/android-sdk-tools-static-aarch64.zip -o tools.zip
  unzip -o tools.zip build-tools/
  mv build-tools/* /usr/local/bin/
  chmod +x /usr/local/bin/*
  rm -rf tools.zip build-tools
  aapt version
  zipalign -v
  echo -e "${GREEN}✓ Android SDK tools (ARM64) ready${NC}" >&2

  echo -e "${YELLOW}Downloading uber-apk-signer...${NC}" >&2
  cd /tmp
  curl -fL https://github.com/patrickfav/uber-apk-signer/releases/download/v1.3.0/uber-apk-signer-1.3.0.jar -o uber-apk-signer.jar
  mv uber-apk-signer.jar /usr/local/bin/
  chmod +x /usr/local/bin/uber-apk-signer.jar
  echo -e "${GREEN}✓ uber-apk-signer ready${NC}" >&2

  echo -e "${YELLOW}Downloading Android Platform JAR (android.jar)...${NC}" >&2
  mkdir -p /usr/local/lib
  cd /tmp
  curl -fL https://raw.githubusercontent.com/Sable/android-platforms/master/android-34/android.jar -o /usr/local/lib/android.jar
  ls -lh /usr/local/lib/android.jar
  echo -e "${GREEN}✓ android.jar installed to /usr/local/lib/${NC}" >&2

  echo -e "${YELLOW}Installing display stack...${NC}" >&2
  pacman -S xorg-server-xvfb x11vnc --noconfirm
  echo -e "${GREEN}✓ VNC + Xvfb ready${NC}" >&2

  echo -e "${YELLOW}Installing window manager...${NC}" >&2
  pacman -S openbox --noconfirm
  echo -e "${GREEN}✓ Openbox installed${NC}" >&2

  echo -e "${YELLOW}Installing GUI apps...${NC}" >&2
  pacman -S dolphin firefox chromium xterm xdg-utils --noconfirm --overwrite "*"
  echo -e "${GREEN}✓ File manager, browsers, and terminal ready${NC}" >&2

  echo -e "${YELLOW}Installing busybox (the real MVP)...${NC}" >&2
  pacman -S busybox --noconfirm
  busybox --install -s
  ln -sf /bin/busybox /bin/sh
  chsh -s /bin/sh
  echo -e "${GREEN}✓ Busybox installed as /bin/sh${NC}" >&2

  echo -e "${YELLOW}Configuring dolphin as default file manager...${NC}" >&2
  xdg-mime default org.kde.dolphin.desktop inode/directory
  xdg-mime default org.kde.dolphin.desktop application/x-cpio
  xdg-mime default org.kde.dolphin.desktop application/x-directory
  echo -e "${GREEN}✓ Dolphin set as default${NC}" >&2

  echo -e "${YELLOW}Creating openbox config...${NC}" >&2
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
  echo -e "${GREEN}✓ Openbox configured${NC}" >&2

  echo -e "${YELLOW}Setting up X11 startup...${NC}" >&2
  cat > /root/.xinitrc << '\''EOF'\''
#!/bin/sh
openbox &
dolphin &
exec tail -f /dev/null
EOF
  chmod +x /root/.xinitrc
  echo -e "${GREEN}✓ Startup script ready${NC}" >&2

  echo -e "${YELLOW}Creating login reminder...${NC}" >&2
  cat > /root/.profile << '\''EOF'\''
# ~/.profile - Login shell startup
cat << '\''BANNER'\''
======== GLIBC/BASH INCOMPATIBILITY NOTICE ========

The Problem:
  Old glibc → bash works, but packages fail
  New glibc → packages work, but bash breaks

The Solution:
  Use busybox sh (lightweight, reliable)

What Works:
  + Firefox (full GUI)
  + Dolphin file manager
  + Busybox utils (vi, sed, awk, grep, find)
  + Java development environment
  + Android build tools (aapt, apksigner)

What Doesn'\''t:
  - bash, nano, vim, neovim
  - Most CLI pacman tools

Tip: Use vi instead of nano, and you'\''re golden!
====================================================
BANNER
EOF
  chmod +x /root/.profile
  echo -e "${GREEN}✓ Login reminder configured${NC}" >&2

  echo -e "${GREEN}=== Setup complete! ===${NC}" >&2
'

echo ""
echo -e "${RED}======== GLIBC/BASH INCOMPATIBILITY NOTICE ========${NC}" >&2
echo ""
echo -e "${YELLOW}The Problem:${NC}" >&2
echo -e "  Old glibc → bash works, but packages fail" >&2
echo -e "  New glibc → packages work, but bash breaks" >&2
echo ""
echo -e "${GREEN}The Solution:${NC}" >&2
echo -e "  Use busybox sh (lightweight, reliable)" >&2
echo ""
echo -e "${GREEN}What Works:${NC}" >&2
echo -e "  + Firefox (full GUI)" >&2
echo -e "  + Dolphin file manager" >&2
echo -e "  + Busybox utils (vi, sed, awk, grep, find)" >&2
echo ""
echo -e "${RED}What Doesn't:${NC}" >&2
echo -e "  - bash, nano, vim, neovim" >&2
echo -e "  - Most CLI pacman tools" >&2
echo ""
echo -e "${YELLOW}Tip: Use ${BLUE}vi${YELLOW} instead of nano, and you're golden!${NC}" >&2
echo -e "${RED}======================================================${NC}" >&2
echo ""
sleep 8
