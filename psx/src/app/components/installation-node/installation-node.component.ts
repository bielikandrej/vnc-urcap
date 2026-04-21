/**
 * Installation-node contribution (v3.9 scaffold).
 *
 * Mirrors the PS5 `VncInstallationNodeView` but in Angular. Lets the operator:
 *   - See portal pairing status (paired / unpaired / error)
 *   - Enter a claim code to pair the robot with portal.stimba.sk
 *   - Toggle VNC enabled / port / password
 *   - See recent command ack log (v3.10)
 *
 * The backend container contribution (v3.10+) will handle:
 *   - Portal HTTPS calls (heartbeat, command poll, ack)
 *   - Primary Interface :30001 URScript sender
 *   - RTDE :30004 reader
 *
 * v3.9 scaffold just renders UI. All the data/send handlers are stubs.
 */
import { Component, OnInit } from "@angular/core";
import {
  InstallationNode,
  TranslateService
} from "@universal-robots/contribution-api";

@Component({
  selector: "stimba-vnc-installation",
  templateUrl: "./installation-node.component.html",
  styleUrls: ["./installation-node.component.scss"]
})
export class InstallationNodeComponent implements OnInit {
  // Node data — persisted by PolyScope X per-installation.
  node!: InstallationNode;

  // UI state
  claimCode = "";
  paired = false;
  portalStatus = "Nespárované";
  agentVersion = "stimba-vnc-urcap/3.9.0-psx";

  // VNC config (mirrors PS5 DataModel keys)
  vnc = {
    enabled: true,
    port: 5900,
    passwordMasked: "••••••••",
    viewOnly: false,
    autostart: true
  };

  constructor(private readonly i18n: TranslateService) {}

  ngOnInit(): void {
    // TODO: load persisted state from node.getProperties() (SDK 0.19 API).
  }

  async pair(): Promise<void> {
    // TODO: POST to backend container: /pair with { claim_code: claimCode }.
    // For v3.9 scaffold we just flip the flag to prove the UI.
    if (!/^STB-[A-Z0-9]{4}-[A-Z0-9]{4}$/.test(this.claimCode)) {
      this.portalStatus = "Neplatný tvar claim kódu (očakáva STB-XXXX-XXXX)";
      return;
    }
    this.paired = true;
    this.portalStatus = "Spárované (scaffold)";
  }

  async unpair(): Promise<void> {
    this.paired = false;
    this.portalStatus = "Nespárované";
    this.claimCode = "";
  }

  async rotateVncPassword(): Promise<void> {
    // TODO: call backend /rotate-vnc-password; here just stub.
    this.vnc.passwordMasked = "•".repeat(16);
  }
}
