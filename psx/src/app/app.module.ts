/**
 * STIMBA VNC URCapX — Angular entry module (v3.9 scaffold).
 *
 * Per the PolyScope X SDK 0.19 docs "My first URCap" tutorial, every URCapX
 * contribution is an Angular module declared in the frontend entry module.
 * This file is a minimal skeleton — v3.10 will wire in the installation
 * node + program nodes.
 */
import { NgModule } from "@angular/core";
import { BrowserModule } from "@angular/platform-browser";
import { FormsModule } from "@angular/forms";
import { UIAngularComponentsModule } from "@universal-robots/ui-angular-components";

import { InstallationNodeComponent } from "./components/installation-node/installation-node.component";

@NgModule({
  declarations: [InstallationNodeComponent],
  imports: [BrowserModule, FormsModule, UIAngularComponentsModule],
  exports: [InstallationNodeComponent]
})
export class AppModule {}
