import { WorkoutPlan } from '../types';

export interface SyncPackage {
  version: 1;
  format: 'itimer-profile';
  exportedAt: string;
  source: 'web' | 'android';
  plan?: WorkoutPlan;
  presets?: WorkoutPlan[];
}

/**
 * Encodes a workout plan into a compact sync URI for QR codes and instant sharing.
 * Format: itimer://plan?d=<base64url>
 */
export function encodePlanToSyncString(plan: WorkoutPlan): string {
  const minimal = {
    n: plan.name,
    p: plan.prepSeconds,
    c: plan.cycles,
    cr: plan.cycleRestSeconds,
    s: plan.sets.map((s) => [s.name, s.workSeconds, s.restSeconds]),
  };
  const jsonStr = JSON.stringify(minimal);
  const base64 = btoa(unescape(encodeURIComponent(jsonStr)));
  return `itimer://plan?d=${base64}`;
}

/**
 * Encodes full workout plan as formatted JSON for file download.
 */
export function encodePlanToItimerJSON(plan: WorkoutPlan): string {
  const pkg: SyncPackage = {
    version: 1,
    format: 'itimer-profile',
    exportedAt: new Date().toISOString(),
    source: 'web',
    plan,
  };
  return JSON.stringify(pkg, null, 2);
}

/**
 * Encodes all presets as formatted bundle JSON.
 */
export function encodeAllPresetsToItimerJSON(presets: WorkoutPlan[]): string {
  const pkg: SyncPackage = {
    version: 1,
    format: 'itimer-profile',
    exportedAt: new Date().toISOString(),
    source: 'web',
    presets,
  };
  return JSON.stringify(pkg, null, 2);
}

/**
 * Decodes a sync string or JSON payload into WorkoutPlan(s).
 */
export function decodeSyncPayload(input: string): {
  plan?: WorkoutPlan;
  presets?: WorkoutPlan[];
  error?: string;
} {
  const trimmed = input.trim();

  // Case 1: Compact itimer URI
  if (trimmed.startsWith('itimer://plan?d=')) {
    try {
      const b64 = trimmed.substring('itimer://plan?d='.length);
      const decodedJson = decodeURIComponent(escape(atob(b64)));
      const min = JSON.parse(decodedJson);
      if (min && typeof min.n === 'string' && Array.isArray(min.s)) {
        const plan: WorkoutPlan = {
          id: `imported-${Date.now()}`,
          name: min.n,
          prepSeconds: Number(min.p) || 0,
          cycles: Number(min.c) || 1,
          cycleRestSeconds: Number(min.cr) || 0,
          isCustom: true,
          createdAt: new Date().toISOString(),
          sets: min.s.map((arr: [string, number, number], idx: number) => ({
            id: `set-${Date.now()}-${idx + 1}`,
            name: String(arr[0] || `Set ${idx + 1}`),
            workSeconds: Number(arr[1]) || 0,
            restSeconds: Number(arr[2]) || 0,
          })),
        };
        return { plan };
      }
    } catch (e) {
      return { error: 'Failed to decode compact itimer URI: ' + (e as Error).message };
    }
  }

  // Case 2: JSON (SyncPackage, direct WorkoutPlan, or array of WorkoutPlan)
  try {
    const parsed = JSON.parse(trimmed);

    // SyncPackage
    if (parsed && parsed.format === 'itimer-profile') {
      if (parsed.plan && Array.isArray(parsed.plan.sets)) {
        return { plan: parsed.plan };
      }
      if (Array.isArray(parsed.presets)) {
        return { presets: parsed.presets };
      }
    }

    // Direct Array of presets
    if (Array.isArray(parsed)) {
      const valid = parsed.filter(
        (p) => p && typeof p.name === 'string' && Array.isArray(p.sets)
      );
      if (valid.length > 0) return { presets: valid };
    }

    // Direct single WorkoutPlan
    if (parsed && typeof parsed.name === 'string' && Array.isArray(parsed.sets)) {
      return { plan: parsed as WorkoutPlan };
    }

    return { error: 'JSON does not match a valid workout plan or preset package.' };
  } catch (e) {
    return { error: 'Invalid JSON format: ' + (e as Error).message };
  }
}
