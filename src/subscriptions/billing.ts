import type * as Iap from 'react-native-iap';
import type { Membership } from './catalog';
export type BillingConfiguration = { enabled: boolean; package_name: string; obfuscated_account_id: string; products: Record<string,string> };
export type BillingResult = Membership & { purchase_state: string; purchase_acknowledged: boolean };
export type BillingStatus = 'unavailable'|'connecting'|'ready'|'pending'|'verifying'|'confirmed'|'cancelled'|'expired'|'inactive'|'error';
export type BillingOffer = { productId: string; token: string; label: string; phases: string[] };
export type NativeBilling = Pick<typeof Iap,'initConnection'|'endConnection'|'fetchProducts'|'getAvailablePurchases'|'requestPurchase'|'finishTransaction'|'purchaseUpdatedListener'|'purchaseErrorListener'>;
/** Tokens stay in memory and travel only over the authenticated Spring transport. */
export class BillingSession {
 private subscriptions: {remove():void}[]=[];
 private processing=new Set<string>();
 private completed=new Set<string>();
 private disposed=false;
 offers: BillingOffer[]=[];
 private native: NativeBilling;
 private config: BillingConfiguration;
 private actor: string;
 private currentActor:()=>string|undefined;
 private verify:(token:string,restore:boolean)=>Promise<BillingResult>;
 private status:(status:BillingStatus,message?:string)=>void;
 constructor(native: NativeBilling,config: BillingConfiguration,actor: string,currentActor:()=>string|undefined,verify:(token:string,restore:boolean)=>Promise<BillingResult>,status:(status:BillingStatus,message?:string)=>void){this.native=native;this.config=config;this.actor=actor;this.currentActor=currentActor;this.verify=verify;this.status=status;}
 private account(){if(this.disposed||this.currentActor()!==this.actor)throw new Error('Your account changed. Reopen Membership to continue.');}
 async connect(){
  this.account();if(!this.config.enabled){this.status('unavailable');return;}
  this.status('connecting');
  this.subscriptions=[this.native.purchaseUpdatedListener(p=>{void this.process(p,false).catch(e=>this.failure(e));}),this.native.purchaseErrorListener(e=>{if(!this.disposed)this.status(String(e.code).includes('user-cancelled')?'cancelled':'error','Purchase was not confirmed. Refresh or restore before retrying.');})];
  try{
   await this.native.initConnection();if(this.disposed){await this.native.endConnection();return;}this.account();
   const products=await this.native.fetchProducts({skus:Object.keys(this.config.products),type:'subs'});this.account();
   this.offers=(products||[]).flatMap(product=>('subscriptionOffers' in product?product.subscriptionOffers||[]:[]).filter(offer=>offer.basePlanIdAndroid===this.config.products[product.id]&&offer.offerTokenAndroid).map(offer=>({productId:product.id,token:offer.offerTokenAndroid!,label:offer.displayPrice,phases:(offer.pricingPhasesAndroid?.pricingPhaseList||[]).map(p=>`${p.formattedPrice} / ${p.billingPeriod}${p.billingCycleCount?` for ${p.billingCycleCount} cycles`:''}`)})));
   this.status(this.offers.length?'ready':'unavailable',this.offers.length?undefined:'No eligible Google Play offers are available.');
  }catch(e){this.failure(e);}
 }
 private failure(_e:unknown){if(!this.disposed && this.currentActor()===this.actor)this.status('error','Billing could not be confirmed. Refresh or restore purchases.');}
 async purchase(offer:BillingOffer,replacement?:{purchaseToken:string;oldProductId:string}){
  this.account();if(!this.config.enabled||!this.offers.some(o=>o.token===offer.token&&o.productId===offer.productId))throw new Error('This offer is unavailable.');
  await this.native.requestPurchase({type:'subs',request:{google:{skus:[offer.productId],obfuscatedAccountId:this.config.obfuscated_account_id,subscriptionOffers:[{sku:offer.productId,offerToken:offer.token}],...(replacement?{purchaseToken:replacement.purchaseToken,subscriptionProductReplacementParams:{oldProductId:replacement.oldProductId,replacementMode:'with-time-proration' as const}}:{})}}});
 }
 async process(purchase:Iap.Purchase,restore:boolean){
  this.account();if(!Object.hasOwn(this.config.products,purchase.productId))return;
  if(purchase.purchaseState==='pending'){this.status('pending','Payment is pending in Google Play. Membership activates after verification.');return;}
  if(purchase.purchaseState!=='purchased'||!purchase.purchaseToken)throw new Error('Google Play has not confirmed this purchase.');
  if('packageNameAndroid' in purchase&&purchase.packageNameAndroid&&purchase.packageNameAndroid!==this.config.package_name)throw new Error('Purchase application does not match.');
  if('obfuscatedAccountIdAndroid' in purchase&&purchase.obfuscatedAccountIdAndroid&&purchase.obfuscatedAccountIdAndroid!==this.config.obfuscated_account_id)throw new Error('This purchase belongs to another Castly account.');
  const token=purchase.purchaseToken;
  if(this.processing.has(token)||(!restore&&this.completed.has(token)))return;
  this.processing.add(token);this.status('verifying');
  try{
   const result=await this.verify(token,restore);this.account();
   if(result.purchase_acknowledged){await this.native.finishTransaction({purchase,isConsumable:false});this.account();}
   this.completed.add(token);
   const state:BillingStatus=result.purchase_state==='pending'?'pending':['expired','revoked'].includes(result.purchase_state)?'expired':['on_hold','paused'].includes(result.purchase_state)?'inactive':'confirmed';
   this.status(state,`Google Play status: ${result.purchase_state}. Membership is verified by Castly.`);
  }finally{this.processing.delete(token);}
 }
 async restore(){this.account();const purchases=await this.native.getAvailablePurchases();this.account();for(const purchase of purchases)await this.process(purchase,true);if(!purchases.length)this.status('ready','No Google Play purchases were found for this store account.');}
 async close(){this.disposed=true;for(const subscription of this.subscriptions)subscription.remove();this.subscriptions=[];this.processing.clear();this.completed.clear();await this.native.endConnection();}
}
