import test from 'node:test';
import assert from 'node:assert/strict';
import { promotionUrl, membershipExpiry } from '../src/subscriptions/catalog.ts';
test('public promotion links are normalized and never fetched by the client',()=>{
 assert.equal(promotionUrl('https://www.instagram.com/reel/abc/?tracking=x'),'https://www.instagram.com/reel/abc');
 for(const bad of ['http://youtube.com/watch/a','https://evil.example/x','https://youtube.com@evil.example/x','https://localhost/a','https://x.com/']) assert.throws(()=>promotionUrl(bad));
});
test('membership expiry comes from server entitlement, including perpetual legacy grants',()=>{
 const base={id:'creator_pro',name:'Creator Pro',portfolio_limit:null,premium_discovery:true,is_creator_pro:true};
 assert.equal(membershipExpiry({...base,entitlements:[{entitlement:'creator_pro',valid_until:null}]}),null);
 assert.equal(membershipExpiry({...base,entitlements:[]}),undefined);
});
