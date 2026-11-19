import { NextResponse } from 'next/server';
import { allowedBackend, cleanQuery, sameOrigin } from '@/lib/security';
import { apiRequest, boundedResponse, boundedInput, configuration } from '@/lib/server';
async function proxy(request: Request, context: { params: Promise<{ path: string[] }> }) {
  try {
    const c = configuration(),
      path = (await context.params).path.join('/'),
      method = request.method;
    if (!allowedBackend(method, path))
      return NextResponse.json({ detail: 'Unavailable' }, { status: 404 });
    if (method !== 'GET' && !sameOrigin(request.headers.get('origin'), c.origin))
      return NextResponse.json({ detail: 'Origin rejected' }, { status: 403 });
    let body: unknown = undefined;
    if (method !== 'GET') {
      if (!request.headers.get('content-type')?.startsWith('application/json'))
        return NextResponse.json({ detail: 'JSON required' }, { status: 415 });
      const bytes = await boundedInput(request, 16384);
      if (bytes.length > 16384)
        return NextResponse.json({ detail: 'Request too large' }, { status: 413 });
      body = bytes ? JSON.parse(bytes) : undefined;
    }
    const query = cleanQuery(new URL(request.url).searchParams);
    const response = await apiRequest(path + (query ? '?' + query : ''), method, body);
    const result = await boundedResponse(response);
    const correlation = response.headers.get('X-Request-ID');
    // Never forward provider/server headers or credentials. Sanitized Spring ProblemDetail only.
    return new Response(result, {
      status: response.status,
      headers: {
        'Content-Type': 'application/json',
        'Cache-Control': 'no-store',
        ...(correlation && /^[0-9a-f-]{36}$/.test(correlation)
          ? { 'X-Request-ID': correlation }
          : {}),
      },
    });
  } catch {
    return NextResponse.json({ detail: 'Administrative service unavailable' }, { status: 503 });
  }
}
export { proxy as GET, proxy as POST, proxy as PATCH, proxy as DELETE };
