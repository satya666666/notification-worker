# Deploy to Azure Container Apps

Infra as code (Bicep): Container Apps (API + worker, autoscaling), internal RabbitMQ, Azure Database for PostgreSQL Flexible Server, Azure Container Registry, Log Analytics.

```bash
az login
DB_PASSWORD='<strong-pw>' RABBIT_PASSWORD='<strong-pw>' ./infra/azure/deploy.sh
```

Then: `curl -X POST <apiUrl>/notifications -H 'Content-Type: application/json' -d '{"to":"a@b.com","subject":"Hi","body":"Hello"}'`

Logs: `az containerapp logs show -n notification-worker -g notification-worker-rg --follow`
Clean up: `az group delete -n notification-worker-rg --yes`

CI/CD: `.github/workflows/deploy-azure.yml` (manual trigger, OIDC login). Needs repo secrets `AZURE_CLIENT_ID`, `AZURE_TENANT_ID`, `AZURE_SUBSCRIPTION_ID`, `DB_PASSWORD`, `RABBIT_PASSWORD` and variable `AZURE_RESOURCE_GROUP`.

Note: RabbitMQ runs without persistent storage (demo); use Azure Service Bus or a mounted Azure Files volume for production. Postgres allows Azure-internal traffic only via firewall rule; use VNet integration for hardening.
