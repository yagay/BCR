#!/usr/bin/env python3
"""Build-only BCR-GUI navigation overlay.

The upstream GUI submodule is intentionally never committed to.
Fails closed if its HTML/TypeScript structure changes.
"""
from pathlib import Path

root = Path(__file__).resolve().parents[2]
gui = root / "external" / "bcr-gui"
marker = "BCR-SUITE-NAV-OVERLAY"

def replace(path: str, old: str, new: str) -> None:
    p = gui / path
    original = p.read_text(encoding="utf-8")
    # Each replacement has its own unique anchor; a file can receive several edits.
    if original.count(old) != 1:
        raise RuntimeError(f"Upstream GUI changed, review overlay: {path}")
    p.write_text(original.replace(old, new), encoding="utf-8")
    print("Suite navigation patched:", path)

# The original GUI menu structure and routing stay intact.
replace(
    "src/app/app.component.html",
    '        <ion-menu-toggle autoHide="true">\n          <ion-item [routerLink]="AppRoutesEnum.About" menuClose>',
    '''        <!-- BCR-SUITE-NAV-OVERLAY: native recorder settings -->
        <ion-menu-toggle autoHide="true">
          <ion-item button (click)="openBcrSettings()" menuClose>
            <ion-icon name="mic-outline" slot="start"></ion-icon>
            <ion-label>BCR · 录音设置</ion-label>
          </ion-item>
        </ion-menu-toggle>

        <ion-menu-toggle autoHide="true">
          <ion-item [routerLink]="AppRoutesEnum.About" menuClose>'''
)
replace(
    "src/app/app.component.ts",
    "import { App } from '@capacitor/app';",
    """// BCR-SUITE-NAV-OVERLAY
import { registerPlugin } from '@capacitor/core';
import { App } from '@capacitor/app';"""
)
replace(
    "src/app/app.component.ts",
    "const TOOLBAR_BACKGROUND_LIGHT = '#43a047';",
    """// BCR-SUITE-NAV-OVERLAY
const BcrSuite = registerPlugin<{ openBcrSettings(): Promise<void> }>('BcrSuite');
const TOOLBAR_BACKGROUND_LIGHT = '#43a047';"""
)
replace(
    "src/app/app.component.ts",
    "  ngOnInit() {",
    """  // BCR-SUITE-NAV-OVERLAY: returns to the GUI on Android Back.
  async openBcrSettings(): Promise<void> {
    try {
      await BcrSuite.openBcrSettings();
    } catch (error) {
      this.mbs.showError({ error });
    }
  }

  ngOnInit() {"""
)
replace(
    "src/app/pages/settings/settings.page.html",
    '    <!-- general section -->',
    '''    <!-- BCR-SUITE-NAV-OVERLAY: keep recording configuration in one app -->
    <ion-item-divider><h4>BCR</h4></ion-item-divider>
    <ion-item button (click)="openBcrSettings()">
      <ion-icon name="mic-outline" slot="start"></ion-icon>
      <ion-label>BCR · 录音设置</ion-label>
    </ion-item>

    <!-- general section -->'''
)
replace(
    "src/app/pages/settings/settings.page.ts",
    "import { Share } from '@capacitor/share';",
    """// BCR-SUITE-NAV-OVERLAY
import { registerPlugin } from '@capacitor/core';
import { Share } from '@capacitor/share';"""
)
replace(
    "src/app/pages/settings/settings.page.ts",
    "@Component({",
    """// BCR-SUITE-NAV-OVERLAY
const BcrSuite = registerPlugin<{ openBcrSettings(): Promise<void> }>('BcrSuite');

@Component({"""
)
replace(
    "src/app/pages/settings/settings.page.ts",
    "  async save() {",
    """  // BCR-SUITE-NAV-OVERLAY
  protected async openBcrSettings(): Promise<void> {
    try {
      await BcrSuite.openBcrSettings();
    } catch (error) {
      this.messageBoxService.showError({ error });
    }
  }

  async save() {"""
)
print("Suite navigation overlay applied (ephemeral build worktree).")
