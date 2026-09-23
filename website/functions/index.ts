import { handler } from './handler.ts';

declare const Deno: {
  serve: (handler: (request: Request) => Response | Promise<Response>) => void;
};

Deno.serve(handler);
