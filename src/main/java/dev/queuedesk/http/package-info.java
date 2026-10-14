/**
 * HTTP layer: thin controllers, the shared error contract, and the request
 * body size limit. Controllers translate HTTP into service calls and shape
 * JSON views; business rules, transactions, and persistence live deeper.
 * Every read endpoint is side-effect free so the dashboard can poll freely.
 */
package dev.queuedesk.http;
