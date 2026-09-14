#!/usr/bin/env node
// The second clinician's half of a controlled-substance disposal, signed on a
// machine that is not the nurse's phone. This stands in for a witness build of
// the app until there is one; the point it demonstrates is that the two halves
// are written from two devices, by two keys, onto one document.
//
//   node tools/witness.mjs http://<node>:<ui-port> [clinician-id]
//
// The key is generated on first run and kept beside this script, so the same
// witness keeps the same key across disposals, the way a handset's keystore
// would. Every open disposal the node holds is signed.

import { createSign, generateKeyPairSync, createPublicKey } from 'node:crypto'
import { readFileSync, writeFileSync, existsSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const base = process.argv[2] || 'http://127.0.0.1:7485'
const who = process.argv[3] || 'rn-a-quinn'
const WASTE = 'unidatum-hospice-waste/v1'
const keyFile = join(dirname(fileURLToPath(import.meta.url)), `witness-${who}.pem`)

if (!existsSync(keyFile)) {
  const { privateKey } = generateKeyPairSync('ec', { namedCurve: 'prime256v1' })
  writeFileSync(keyFile, privateKey.export({ type: 'pkcs8', format: 'pem' }), { mode: 0o600 })
  console.log(`generated a key for ${who} at ${keyFile}`)
}
const priv = readFileSync(keyFile, 'utf8')
const pubB64 = createPublicKey(priv).export({ type: 'spki', format: 'der' }).toString('base64')

const post = async (path, body) => {
  const r = await fetch(base + path, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(body),
  })
  const j = await r.json()
  if (j.error) throw new Error(j.error)
  return j
}

// A node answers find by asking peers when it lacks the members, and then
// refuses the update because an update wants every member local. Replicate
// first, so this node holds the collection it is about to write to.
await post('/api/table/replicate', { table: 'medications' }).catch(() => {})

const all = (await post('/api/doc/find', { collection: 'medications', filter: {} })).documents || []
const open = all.filter((m) => m.waste_signature && !m.witness_signature)
if (open.length === 0) {
  console.log('no disposal is waiting for a witness')
  process.exit(0)
}

for (const m of open) {
  const at = new Date().toISOString().replace('Z', '000Z')
  const claim = [WASTE, 'witness', who, m._id, m.drug, m.wasted, m.method, at].join('|')
  const s = createSign('SHA256')
  s.update(claim)
  s.end()
  const sig = s.sign(priv).toString('base64')
  const r = await post('/api/doc/update', {
    collection: 'medications',
    filter: { _id: m._id },
    update: { $set: { witness_id: who, witnessed_at: at, witness_claim: claim, witness_signature: sig, witness_public_key: pubB64 } },
  })
  console.log(`${who} witnessed ${m._id} (${m.drug}, ${m.wasted}) — matched ${r.matched}`)
}
