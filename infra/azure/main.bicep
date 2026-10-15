// Azure Container Apps deployment for notification-worker.
// Resources: Log Analytics, Container Registry, Container Apps Environment,
// PostgreSQL Flexible Server, RabbitMQ (internal container app), API + Worker container apps.
targetScope = 'resourceGroup'

@description('Base name used to derive resource names.')
param baseName string = 'notifworker'
param location string = resourceGroup().location

@description('Container image for API/worker (e.g. <acr>.azurecr.io/notification-worker:<tag>). Empty on first run, a public placeholder is used.')
param image string = ''

@description('PostgreSQL administrator login.')
param dbAdminUser string = 'notifadmin'
@secure()
param dbAdminPassword string
@secure()
param rabbitPassword string

var suffix = uniqueString(resourceGroup().id)
var acrName = toLower('${baseName}${suffix}')
var placeholderImage = 'mcr.microsoft.com/k8se/quickstart:latest'
var appImage = empty(image) ? placeholderImage : image
var useAcr = !empty(image)

resource logs 'Microsoft.OperationalInsights/workspaces@2023-09-01' = {
  name: '${baseName}-logs-${suffix}'
  location: location
  properties: {
    sku: { name: 'PerGB2018' }
    retentionInDays: 30
  }
}

resource acr 'Microsoft.ContainerRegistry/registries@2023-07-01' = {
  name: acrName
  location: location
  sku: { name: 'Basic' }
  properties: { adminUserEnabled: true }
}

resource env 'Microsoft.App/managedEnvironments@2024-03-01' = {
  name: '${baseName}-env'
  location: location
  properties: {
    appLogsConfiguration: {
      destination: 'log-analytics'
      logAnalyticsConfiguration: {
        customerId: logs.properties.customerId
        sharedKey: logs.listKeys().primarySharedKey
      }
    }
  }
}

resource pg 'Microsoft.DBforPostgreSQL/flexibleServers@2023-12-01-preview' = {
  name: '${baseName}-pg-${suffix}'
  location: location
  sku: { name: 'Standard_B1ms', tier: 'Burstable' }
  properties: {
    version: '15'
    administratorLogin: dbAdminUser
    administratorLoginPassword: dbAdminPassword
    storage: { storageSizeGB: 32 }
    backup: { backupRetentionDays: 7, geoRedundantBackup: 'Disabled' }
    highAvailability: { mode: 'Disabled' }
  }
}

resource pgDb 'Microsoft.DBforPostgreSQL/flexibleServers/databases@2023-12-01-preview' = {
  parent: pg
  name: 'notification'
}

// Allows Azure services (including Container Apps) to reach the server. Tighten with VNet integration for production.
resource pgFirewall 'Microsoft.DBforPostgreSQL/flexibleServers/firewallRules@2023-12-01-preview' = {
  parent: pg
  name: 'AllowAzureServices'
  properties: { startIpAddress: '0.0.0.0', endIpAddress: '0.0.0.0' }
}

// RabbitMQ broker, reachable only inside the Container Apps environment.
resource rabbit 'Microsoft.App/containerApps@2024-03-01' = {
  name: 'rabbitmq'
  location: location
  properties: {
    managedEnvironmentId: env.id
    configuration: {
      ingress: {
        external: false
        transport: 'tcp'
        targetPort: 5672
        exposedPort: 5672
      }
      secrets: [{ name: 'rabbit-pass', value: rabbitPassword }]
    }
    template: {
      containers: [
        {
          name: 'rabbitmq'
          image: 'rabbitmq:3-management'
          resources: { cpu: json('0.5'), memory: '1Gi' }
          env: [
            { name: 'RABBITMQ_DEFAULT_USER', value: 'notification' }
            { name: 'RABBITMQ_DEFAULT_PASS', secretRef: 'rabbit-pass' }
          ]
        }
      ]
      scale: { minReplicas: 1, maxReplicas: 1 }
    }
  }
}

var sharedEnv = [
  { name: 'DB_HOST', value: pg.properties.fullyQualifiedDomainName }
  { name: 'DB_PORT', value: '5432' }
  { name: 'DB_NAME', value: 'notification' }
  { name: 'DB_USER', value: dbAdminUser }
  { name: 'DB_PASSWORD', secretRef: 'db-pass' }
  { name: 'RABBITMQ_HOST', value: 'rabbitmq' }
  { name: 'RABBITMQ_USER', value: 'notification' }
  { name: 'RABBITMQ_PASSWORD', secretRef: 'rabbit-pass' }
]
var sharedSecrets = [
  { name: 'db-pass', value: dbAdminPassword }
  { name: 'rabbit-pass', value: rabbitPassword }
]
var registries = useAcr ? [
  { server: acr.properties.loginServer, username: acr.listCredentials().username, passwordSecretRef: 'acr-pass' }
] : []
var acrSecrets = useAcr ? [{ name: 'acr-pass', value: acr.listCredentials().passwords[0].value }] : []

resource api 'Microsoft.App/containerApps@2024-03-01' = {
  name: 'notification-api'
  location: location
  properties: {
    managedEnvironmentId: env.id
    configuration: {
      ingress: { external: true, targetPort: useAcr ? 8080 : 80, transport: 'auto' }
      registries: registries
      secrets: concat(sharedSecrets, acrSecrets)
    }
    template: {
      containers: [
        {
          name: 'api'
          image: appImage
          resources: { cpu: json('0.5'), memory: '1Gi' }
          env: concat(sharedEnv, [
            { name: 'NOTIFICATION_ROLE_API', value: 'true' }
            { name: 'NOTIFICATION_ROLE_WORKER', value: 'false' }
          ])
          probes: useAcr ? [
            { type: 'Readiness', httpGet: { path: '/actuator/health', port: 8080 }, initialDelaySeconds: 30, periodSeconds: 10 }
          ] : []
        }
      ]
      scale: {
        minReplicas: 1
        maxReplicas: 3
        rules: [{ name: 'http-scale', http: { metadata: { concurrentRequests: '50' } } }]
      }
    }
  }
  dependsOn: [rabbit, pgDb, pgFirewall]
}

resource worker 'Microsoft.App/containerApps@2024-03-01' = {
  name: 'notification-worker'
  location: location
  properties: {
    managedEnvironmentId: env.id
    configuration: {
      registries: registries
      secrets: concat(sharedSecrets, acrSecrets)
    }
    template: {
      containers: [
        {
          name: 'worker'
          image: appImage
          resources: { cpu: json('0.5'), memory: '1Gi' }
          env: concat(sharedEnv, [
            { name: 'NOTIFICATION_ROLE_API', value: 'false' }
            { name: 'NOTIFICATION_ROLE_WORKER', value: 'true' }
          ])
        }
      ]
      scale: { minReplicas: 1, maxReplicas: 5 }
    }
  }
  dependsOn: [rabbit, pgDb, pgFirewall]
}

output acrName string = acr.name
output acrLoginServer string = acr.properties.loginServer
output apiUrl string = 'https://${api.properties.configuration.ingress.fqdn}'
