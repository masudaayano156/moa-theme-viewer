/** Retired push relay. No database binding, scheduler or outbound requests. */
export default {
  async fetch(request) {
    const health = request.method === 'GET' && new URL(request.url).pathname === '/health';
    return Response.json({ status: 'retired', code: 'push_retired',
      message: 'Background push has ended. Update Moa for in-app notifications.' }, {
      status: health ? 200 : 410,
      headers: { 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff' }
    });
  },
  async scheduled() { /* Old in-flight scheduler events intentionally do nothing. */ }
};
