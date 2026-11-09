import React, { useEffect, useState } from 'react';
import { Text, View } from 'react-native';
import { router } from 'expo-router';
import { Screen, PrimaryButton } from '../components/ui';
import { c, f } from '../theme';
import { services } from '../backend';
import { useAuth } from '../auth/AuthProvider';
import { membershipExpiry, type Membership, type Plan, type SubscriptionOrder } from './catalog';
export default function SubscriptionScreen() {
 const {session}=useAuth();
 const [plans,setPlans]=useState<Plan[]>([]),[membership,setMembership]=useState<Membership|null>(null),[orders,setOrders]=useState<SubscriptionOrder[]>([]),[error,setError]=useState(''),[busy,setBusy]=useState(false);
 const load=async()=>{setBusy(true);setError('');try {if(!services)throw new Error('Sign in to view memberships.'); const p=await services.subscriptions.plans();setPlans(p);if(session){const [m,o]=await Promise.all([services.subscriptions.current(),services.subscriptions.history()]);setMembership(m);setOrders(o);}}catch(e){setError(e instanceof Error?e.message:'Memberships are unavailable.');}finally{setBusy(false);}};
 useEffect(()=>{void load();},[session?.user.id]); // eslint-disable-line react-hooks/exhaustive-deps
 const expiry=membership?membershipExpiry(membership):undefined;
 return <Screen title="Membership"><Text style={{fontFamily:f.bold,fontSize:24,color:c.text}}>Create more with Castly</Text><Text>Bookings and messaging are included in Free. Castly takes no booking commission.</Text>
 {error?<Text accessibilityRole="alert" style={{color:c.error}}>{error}</Text>:null}
 {membership?<View><Text style={{fontFamily:f.bold}}>Your membership: {membership.name}</Text>{membership.is_creator_pro?<Text>{expiry?`Creator Pro through ${new Date(expiry).toLocaleDateString()}`:'Creator Pro is active'}</Text>:null}</View>:null}
 {plans.map(plan=><View key={plan.id} style={{padding:20,borderRadius:20,backgroundColor:c.white,borderColor:c.border,borderWidth:1,gap:12}}><Text style={{fontFamily:f.bold,fontSize:20}}>{plan.name}</Text><Text>{plan.premium_discovery?'Premium discovery eligibility and enhanced portfolio options.':'Marketplace access, standard profile, portfolio, bookings and messaging.'}</Text>{plan.portfolio_limit!==null?<Text>Up to {plan.portfolio_limit} portfolio items</Text>:null}{plan.id==='creator_pro'?<><Text>Pricing will appear from Google Play when purchases are available.</Text><PrimaryButton title="Purchases unavailable" disabled onPress={()=>{}} /></>:null}</View>)}
 {orders.map(o=><Text key={o.id}>{o.state} · {o.auto_renewing?'Renews automatically':'Does not renew'}{o.expires_at?` · ${new Date(o.expires_at).toLocaleDateString()}`:''}</Text>)}
 <PrimaryButton title={busy?'Refreshing…':'Refresh membership'} disabled={busy} onPress={()=>void load()}/>
 <PrimaryButton title="Founding Creator Offer" onPress={()=>router.push('/subscriptions/founding')}/>
 </Screen>;
}
