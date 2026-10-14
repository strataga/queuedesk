/**
 * Root application providers: router, HttpClient, and global error listeners.
 * The app is zoneless (no zone.js), so manual RxJS subscriptions in pages
 * mark change detection explicitly where they assign fields.
 */
import { ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import { provideRouter } from '@angular/router';
import { routes } from './app.routes';

export const appConfig: ApplicationConfig = {
  providers: [provideBrowserGlobalErrorListeners(), provideRouter(routes), provideHttpClient()],
};
