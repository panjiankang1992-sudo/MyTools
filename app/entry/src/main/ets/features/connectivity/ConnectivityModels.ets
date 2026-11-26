export interface LanBootstrap {
  instanceId: string;
  serviceType: string;
  apiVersion: string;
  probeId: string;
  proofKey: string;
  expiresAt: number;
  lanAddresses: string[];
  lanPort: number;
  ipv6DirectOrigins?: string[];
  directConfigExpiresAt?: number;
}

export interface LanChallenge {
  instanceId: string;
  apiVersion: string;
  nonce: string;
  expiresAt: number;
  proof: string;
}

export interface LanEndpointResult {
  baseUrl: string;
  instanceId: string;
  verifiedIpv6Origins?: string[];
  verifiedUntil?: number;
}
