import React,{useEffect,useRef,useState} from 'react';
import {Linking,Text,View} from 'react-native';
import {useAuth} from '../auth/AuthProvider';
import {services} from '../backend';
import {PrimaryButton} from '../components/ui';
import {BillingSession,type BillingOffer,type BillingStatus} from './billing';
import {loadNativeBilling} from './billingNative';
export function PlayBilling({onVerified}:{onVerified:()=>void}){
 const {session}=useAuth();const actor=session?.user.id;const current=useRef(actor);current.current=actor;const refreshed=useRef(onVerified);refreshed.current=onVerified;
 const billing=useRef<BillingSession|null>(null);const [offers,setOffers]=useState<BillingOffer[]>([]),[status,setStatus]=useState<BillingStatus>('unavailable'),[message,setMessage]=useState('Purchases require a supported Android build and configured Google Play products.'),[busy,setBusy]=useState(false);
 useEffect(()=>{let active=true;let owned:BillingSession|null=null;setOffers([]);setStatus('unavailable');
  void(async()=>{if(!services||!actor)return;try{const config=await services.subscriptions.billingConfiguration();if(!active||!config.enabled)return;const native=await loadNativeBilling();if(!active||!native)return;owned=new BillingSession(native,config,actor,()=>current.current,(token,restore)=>restore?services!.subscriptions.restore(token,actor):services!.subscriptions.verify(token,actor),(s,m)=>{if(active){setStatus(s);if(m)setMessage(m);if(['confirmed','expired','inactive'].includes(s))refreshed.current();}});billing.current=owned;await owned.connect();if(active)setOffers(owned.offers);}catch(e){if(active){setStatus('error');setMessage('Billing is unavailable. Refresh membership or try again.');}}})();
  return()=>{active=false;billing.current=null;if(owned)void owned.close();};
 },[actor]);
 const run=async(action:()=>Promise<unknown>)=>{const started=current.current;setBusy(true);try{await action();}catch(e){if(current.current!==started)return;setStatus('error');setMessage('Purchase could not be confirmed. Refresh or restore before retrying.');}finally{if(current.current===started)setBusy(false);}};
 return <View style={{gap:12}}><Text accessibilityRole="header">Google Play subscription</Text><Text accessibilityRole={status==='error'?'alert':undefined}>{message}</Text>
 {offers.map(offer=><View key={offer.token}><Text>{offer.phases.join(' · ')||offer.label}</Text><PrimaryButton title={`Subscribe · ${offer.label}`} disabled={busy||['pending','verifying','connecting'].includes(status)} onPress={()=>void run(()=>billing.current!.purchase(offer))}/></View>)}
 {!offers.length?<PrimaryButton title="Purchases unavailable" disabled onPress={()=>{}}/>:null}
 <PrimaryButton title={busy?'Please wait…':'Restore purchases'} disabled={busy||!billing.current} onPress={()=>void run(()=>billing.current!.restore())}/>
 <PrimaryButton title="Manage in Google Play" onPress={()=>void Linking.openURL('https://play.google.com/store/account/subscriptions')}/></View>;
}
