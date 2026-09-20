import mdns from "multicast-dns";
import * as http from "http";
import { EventEmitter } from "events";

export interface DiscoveredDevice {
  name: string;
  address: string; // ip:port
  ip: string;
  port: number;
  lastSeenAt: number;
}

export const mdnsEvents = new EventEmitter();
const deviceCache = new Map<string, DiscoveredDevice>();
const TTL_MS = 5 * 60 * 1000; // 5 minutes

let mdnsInstance: any = null;

export function startMdnsDaemon() {
  if (mdnsInstance) return;
  
  try {
    mdnsInstance = mdns();
  } catch {
    return;
  }

  const SERVICE = "_moonclicker-workbench._tcp.local";

  mdnsInstance.on("response", (response: any) => {
    const ptrAnswers = response.answers.filter((a: any) => a.type === "PTR" && a.name === SERVICE);
    if (ptrAnswers.length === 0) return;

    for (const ptr of ptrAnswers) {
      const instanceName = ptr.data;
      const srv = response.additionals.find((a: any) => a.type === "SRV" && a.name === instanceName)
               || response.answers.find((a: any) => a.type === "SRV" && a.name === instanceName);
      
      if (srv) {
        const target = srv.data.target;
        const aRec = response.additionals.find((a: any) => a.type === "A" && a.name === target)
                  || response.answers.find((a: any) => a.type === "A" && a.name === target);
        if (aRec) {
          const ip = aRec.data;
          const port = srv.data.port;
          const name = instanceName.replace("._moonclicker-workbench._tcp.local", "").trim();
          const address = `${ip}:${port}`;
          
          const isNew = !deviceCache.has(address);
          deviceCache.set(address, { name, address, ip, port, lastSeenAt: Date.now() });
          
          if (isNew) {
            mdnsEvents.emit("deviceAdded", deviceCache.get(address));
          }
        }
      }
    }
  });

  // Query immediately on start
  refreshMdns();
}

export function refreshMdns() {
  if (mdnsInstance) {
    try {
      mdnsInstance.query({ questions: [{ name: "_moonclicker-workbench._tcp.local", type: "PTR" }] });
    } catch {}
  }
}

export function getCachedDevices(): DiscoveredDevice[] {
  const now = Date.now();
  const validDevices: DiscoveredDevice[] = [];
  for (const [address, dev] of deviceCache.entries()) {
    if (now - dev.lastSeenAt > TTL_MS) {
      deviceCache.delete(address);
    } else {
      validDevices.push(dev);
    }
  }
  return validDevices;
}

export async function checkDeviceHealth(ip: string, port: number): Promise<boolean> {
  return new Promise((resolve) => {
    const req = http.get(`http://${ip}:${port}/health`, { timeout: 800 }, (res) => {
      resolve(res.statusCode === 200);
    });
    req.on("error", () => resolve(false));
    req.on("timeout", () => {
      req.destroy();
      resolve(false);
    });
  });
}
