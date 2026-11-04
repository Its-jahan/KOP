// Main-thread client for the engine worker. Cancellation terminates and recreates
// the worker (then reloads the graph), so a long plan can never block newer work.

import type { GraphSnapshot } from '../core/types';
import type { WorkerRequest, WorkerResponse } from './engine.worker';

type Pending = { resolve: (r: WorkerResponse) => void; reject: (e: Error) => void };
type DistributiveOmit<T, K extends keyof never> = T extends unknown ? Omit<T, K> : never;

export class Engine {
  private worker: Worker;
  private nextId = 1;
  private pending = new Map<number, Pending>();
  private graph: GraphSnapshot | null = null;

  constructor() {
    this.worker = this.spawn();
  }

  private spawn(): Worker {
    const w = new Worker(new URL('./engine.worker.ts', import.meta.url), { type: 'module' });
    w.onmessage = (ev: MessageEvent<WorkerResponse>) => {
      const p = this.pending.get(ev.data.id);
      if (!p) return;
      this.pending.delete(ev.data.id);
      p.resolve(ev.data);
    };
    w.onerror = (ev) => {
      for (const p of this.pending.values()) p.reject(new Error(ev.message || 'worker_error'));
      this.pending.clear();
    };
    return w;
  }

  call<T extends Extract<WorkerResponse, { ok: true }>['type']>(req: DistributiveOmit<WorkerRequest, 'id'>): Promise<Extract<WorkerResponse, { type: T; ok: true }>> {
    const id = this.nextId++;
    return new Promise((resolve, reject) => {
      this.pending.set(id, {
        resolve: (r) => (r.ok ? resolve(r as Extract<WorkerResponse, { type: T; ok: true }>) : reject(new Error((r as { error: string }).error))),
        reject,
      });
      this.worker.postMessage({ ...req, id } as WorkerRequest);
    });
  }

  async loadGraph(g: GraphSnapshot) {
    this.graph = g;
    await this.call<'load'>({ type: 'load', graph: g });
  }

  setGraph(g: GraphSnapshot) {
    this.graph = g;
  }

  /** Abort everything in flight and restart the worker with the current graph. */
  async cancelAll() {
    this.worker.terminate();
    for (const p of this.pending.values()) p.reject(new Error('cancelled'));
    this.pending.clear();
    this.worker = this.spawn();
    if (this.graph) await this.loadGraph(this.graph);
  }

  dispose() {
    this.worker.terminate();
  }
}
