/**
 * Application services: the use-case boundary for idempotent admission and
 * consistent reads. Services own transaction boundaries and compose the
 * repository, messaging, worker, and risk packages into one payment story.
 */
package dev.queuedesk.service;
