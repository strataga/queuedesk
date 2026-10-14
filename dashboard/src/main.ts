/**
 * Dashboard entry point: bootstraps the standalone Angular app.
 * All providers live in appConfig; routing lives in app.routes.
 */
import { bootstrapApplication } from '@angular/platform-browser';
import { appConfig } from './app/app.config';
import { App } from './app/app';

bootstrapApplication(App, appConfig).catch((err) => console.error(err));
