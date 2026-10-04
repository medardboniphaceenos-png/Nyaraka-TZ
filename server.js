/**
 * DocuConvert - File Conversion & Compression Backend Server
 * Mobile Money Integrations & File Tool Endpoints
 *
 * Provides endpoints for:
 * 1. Serving frontend single-page web app
 * 2. Mobile money payment simulation & webhook verification (M-Pesa, Tigo Pesa, Airtel Money, HaloPesa)
 * 3. File conversion metadata & health checks
 */

const http = require('http');
const fs = require('fs');
const path = require('path');

const PORT = process.env.PORT || 3000;

// In-memory simulated payment transaction ledger
const transactions = new Map();

const MIME_TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.pdf': 'application/pdf',
  '.docx': 'application/vnd.openxmlformats-officedocument.wordprocessingml.document'
};

const server = http.createServer((req, res) => {
  // CORS Headers for API calls
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type');

  if (req.method === 'OPTIONS') {
    res.writeHead(204);
    res.end();
    return;
  }

  const url = new URL(req.url, `http://${req.headers.host}`);

  // API ROUTE: Initiate Mobile Money USSD Push (Tsh 200)
  if (url.pathname === '/api/payment/push-ussd' && req.method === 'POST') {
    let body = '';
    req.on('data', chunk => { body += chunk; });
    req.on('end', () => {
      try {
        const payload = JSON.parse(body || '{}');
        const phone = payload.phone || '0754123456';
        const provider = payload.provider || 'mpesa';
        const amount = 200; // Fixed Tsh 200 micro-fee

        const txnId = 'DC-' + provider.toUpperCase() + '-' + Date.now().toString().slice(-6);

        // Record initial pending transaction
        transactions.set(txnId, {
          txnId,
          phone,
          provider,
          amount,
          status: 'PENDING',
          createdAt: new Date().toISOString()
        });

        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({
          success: true,
          message: `Ombi la malipo ya Tsh ${amount} limetumwa kwa ${phone} kupitia ${provider.toUpperCase()}`,
          transactionId: txnId,
          amount: amount,
          status: 'PENDING'
        }));
      } catch (err) {
        res.writeHead(400, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ success: false, error: 'Invalid JSON request payload' }));
      }
    });
    return;
  }

  // API ROUTE: Verify / Check Mobile Money Payment Status
  if (url.pathname === '/api/payment/verify' && req.method === 'GET') {
    const txnId = url.searchParams.get('txnId');
    if (!txnId) {
      res.writeHead(400, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ success: false, error: 'Kosa: txnId inahitajika' }));
      return;
    }

    const txn = transactions.get(txnId) || {
      txnId,
      amount: 200,
      status: 'COMPLETED', // Auto-approve simulation
      verifiedAt: new Date().toISOString()
    };

    txn.status = 'COMPLETED';
    transactions.set(txnId, txn);

    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({
      success: true,
      transactionId: txnId,
      status: 'COMPLETED',
      amount: txn.amount || 200,
      downloadToken: 'TKN-' + Math.random().toString(36).substring(2, 10).toUpperCase()
    }));
    return;
  }

  // API ROUTE: Server Health Check
  if (url.pathname === '/api/health') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({
      status: 'ok',
      service: 'Nyaraka TZ Converter API',
      region: 'Tanzania / East Africa',
      supportedFeatures: [
        'PDF to Word Conversion',
        'Image/PDF Compression',
        'Images to PDF Unification',
        'Mobile Money USSD Simulation (Tsh 200)'
      ]
    }));
    return;
  }

  // STATIC FILE SERVING
  let filePath = path.join(__dirname, url.pathname === '/' ? 'index.html' : url.pathname);
  if (!fs.existsSync(filePath)) {
    filePath = path.join(__dirname, 'index.html');
  }

  const ext = path.extname(filePath).toLowerCase();
  const contentType = MIME_TYPES[ext] || 'application/octet-stream';

  fs.readFile(filePath, (err, content) => {
    if (err) {
      res.writeHead(500, { 'Content-Type': 'text/plain' });
      res.end('Server Error loading file');
    } else {
      res.writeHead(200, { 'Content-Type': contentType });
      res.end(content);
    }
  });
});

if (require.main === module) {
  server.listen(PORT, () => {
    console.log(`[Nyaraka TZ] Server running on port ${PORT}`);
    console.log(`Visit http://localhost:${PORT} in your browser.`);
  });
}

module.exports = server;
