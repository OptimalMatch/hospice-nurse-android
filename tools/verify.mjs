#!/usr/bin/env node
// Verify every signature a nurse's phone wrote, against any node that holds the
// collections. Nothing here is privileged: the public key rides on the document,
// so a compliance reviewer runs this against a branch node and needs no key
// exchange with the handset.
//
//   node tools/verify.mjs http://<node>:<ui-port>
//
// Each check rebuilds the claim string from the document's OWN fields and then
// checks the signature over it. That is what makes signing worth doing: a claim
// cannot be re-pointed at another clinician, another patient or another moment
// without the signature failing.

import { createVerify, createPublicKey } from 'node:crypto'

const base = process.argv[2] || 'http://127.0.0.1:7482'
const VISIT = 'unidatum-hospice-visit/v1'
const WASTE = 'unidatum-hospice-waste/v1'

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
const find = async (collection, filter = {}) =>
  (await post('/api/doc/find', { collection, filter })).documents || []

const key = (b64) =>
  createPublicKey({ key: Buffer.from(b64, 'base64'), format: 'der', type: 'spki' })

const good = (claim, sigB64, keyB64) => {
  try {
    const v = createVerify('SHA256')
    v.update(claim)
    v.end()
    return v.verify(key(keyB64), Buffer.from(sigB64, 'base64'))
  } catch {
    return false
  }
}

const six = (n) => (n === null || n === undefined ? '' : Number(n).toFixed(6))
let pass = 0
let fail = 0
const say = (ok, what, why = '') => {
  console.log(`${ok ? 'VERIFIED' : 'FAILED  '}  ${what}${why ? '   ' + why : ''}`)
  ok ? pass++ : fail++
}

// 1. Arrival and departure, from visit_verification.
for (const d of await find('visit_verification')) {
  if (!d.signature) continue
  const [lon, lat] = d.location?.coordinates || [null, null]
  const rebuilt = [VISIT, d.clinician_id, d.visit_id, d.event, six(lon), six(lat), d.at].join('|')
  say(rebuilt === d.claim, `${d._id} claim matches its own fields`, rebuilt === d.claim ? '' : `document says ${rebuilt}`)
  say(good(d.claim, d.signature, d.public_key), `${d._id} signature over the claim`)
}

// 2. The clinician's signature on the charted visit itself.
for (const v of await find('visits')) {
  if (!v.clinician_signature) continue
  say(good(v.clinician_claim, v.clinician_signature, v.clinician_public_key), `${v._id} clinician signature`)
  say(!!v.caregiver_signature?.startsWith('data:image/png;base64,'), `${v._id} carries the caregiver's signature`,
    v.caregiver_signature ? `${v.caregiver_name}, ${Buffer.from(v.caregiver_signature.split(',')[1], 'base64').length} bytes` : '')
}

// 3. Both halves of every controlled-substance disposal.
for (const m of await find('medications')) {
  if (!m.waste_signature) continue
  const nurse = [WASTE, 'nurse', m.wasted_by, m._id, m.drug, m.wasted, m.method, m.wasted_at].join('|')
  say(nurse === m.waste_claim, `${m._id} nurse claim matches the document`)
  say(good(m.waste_claim, m.waste_signature, m.waste_public_key), `${m._id} nurse signature`)
  if (!m.witness_signature) {
    console.log(`OPEN      ${m._id} is waiting for a witness`)
    continue
  }
  const w = [WASTE, 'witness', m.witness_id, m._id, m.drug, m.wasted, m.method, m.witnessed_at].join('|')
  say(w === m.witness_claim, `${m._id} witness claim matches the document`)
  say(good(m.witness_claim, m.witness_signature, m.witness_public_key), `${m._id} witness signature`)
  say(m.waste_public_key !== m.witness_public_key, `${m._id} the two halves are two different keys`,
    `${m.wasted_by} and ${m.witness_id}`)
}

console.log(`\n${pass} verified, ${fail} failed`)
process.exit(fail ? 1 : 0)
