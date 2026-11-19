typedef unsigned long U; typedef unsigned int W; typedef unsigned char B;
extern int mprotect(void*,U,int);
static volatile int settings_seen;
static volatile int shop_seen, pending[2], applied[2];
static U gamebase;
static void currency_tick(void){
 if(!gamebase)return;
 B*player=*(B**)(gamebase+0x2152030);if(!player)return;
 int(*get)(B*)=(void*)(gamebase+0x3ec674);
 void(*set)(B*,int)=(void*)(gamebase+0x3ed3f0);
 for(int i=0;i<2;i++){
  int count=__atomic_exchange_n(pending+i,0,__ATOMIC_ACQ_REL);if(!count)continue;
  B*field=player+(i?0x1c8:0x1b0);int current=get(field);
  if(current>=0&&current<=2147482647&&count<=1000){long next=(long)current+1000L*count;if(next<=2147483647){set(field,(int)next);__atomic_fetch_add(applied+i,count,__ATOMIC_RELEASE);}}
 }
}
static int localshop(B*p){
 int found=0;for(int d=0;p&&d<128;d++,p=*(B**)(p+0x38)){
  if(!(*(W*)(p+0xe8)&2))return 0;
  const char*n=(p[0x18]&1)?*(char**)(p+0x28):(char*)(p+0x19);
  const char*s="store_content";int i=0;while(s[i]&&s[i]==n[i])i++;if(!s[i]&&!n[i])found=1;
 }return found;
}
static int eq(const char*a,const char*b){if(!a||!b)return 0;for(int i=0;i<160;i++){if(a[i]!=b[i])return 0;if(!a[i])return 1;}return 0;}
static int has(const char*a,const char*b){if(!a)return 0;for(int i=0;i<160&&a[i];i++){int j=0;while(b[j]&&i+j<160&&a[i+j]==b[j])j++;if(!b[j])return 1;}return 0;}
static const char* name(B*p){return(p[0x18]&1)?*(char**)(p+0x28):(char*)(p+0x19);}
static int denied(B*p){int settings=0,allowed=0,button=0;for(int depth=0;p&&depth<48;depth++,p=*(B**)(p+0x38)){const char*n=name(p);if(eq(n,"store_navigation"))return 1;if(has(n,"vip")||has(n,"VIP")||eq(n,"store_button")||eq(n,"event_hub_btn")||eq(n,"group_event_hub")||eq(n,"offers_align_group")||has(n,"sign_in")||has(n,"signin")||has(n,"composed_activity")||has(n,"daily_sign")||has(n,"dailySign")||has(n,"daily_login")||eq(n,"signin_button")||eq(n,"activity_button")||eq(n,"activity_btn"))return 1;if(has(n,"settings")&&!eq(n,"settings_btn")&&!eq(n,"settings_button"))settings=1;if(eq(n,"settings_btn")||eq(n,"settings_button")||eq(n,"toggle_music")||eq(n,"toggle_sounds")||eq(n,"button_cancel")||eq(n,"button_exit")||eq(n,"button_music")||eq(n,"button_sound")||has(n,"close")||eq(n,"button_back"))allowed=1;if(has(n,"button_")||has(n,"_button")||has(n,"_btn"))button=1;}return settings&&button&&!allowed;}
static int visible(B*p){currency_tick();if(localshop(p)){__atomic_store_n(&shop_seen,1,__ATOMIC_RELEASE);return 0;}if(denied(p)){*(W*)(p+0xe8)&=~6u;return 0;}for(int d=0;p&&d<128;d++,p=*(B**)(p+0x38)){if(!(*(W*)(p+0xe8)&2))return 0;const char*n=name(p);if(has(n,"settings")&&!eq(n,"settings_btn")&&!eq(n,"settings_button"))__atomic_store_n(&settings_seen,1,__ATOMIC_RELEASE);}return 1;}
static void flush(U a,U b){for(U p=a&~63UL;p<b;p+=64)__asm__ volatile("dc cvau, %0"::"r"(p):"memory");__asm__ volatile("dsb ish":::"memory");for(U p=a&~63UL;p<b;p+=64)__asm__ volatile("ic ivau, %0"::"r"(p):"memory");__asm__ volatile("dsb ish; isb":::"memory");}
__attribute__((visibility("default"))) int Java_local_simcity_offline_UiCleaner_install(void*e,void*c,long base){gamebase=(U)base;B*p=(B*)(base+0x1ae72f4);if(*(W*)p!=0xf9401c01||*(W*)(p+4)!=0xb4000201)return 0;*(U*)(p+8)=(U)visible;*(W*)(p+4)=0xd61f0200;__asm__ volatile("dsb ish":::"memory");*(W*)p=0x58000050;flush((U)p,(U)p+16);return 1;}
__attribute__((visibility("default"))) int Java_local_simcity_offline_UiCleaner_pollSettingsVisible(void*e,void*c){return __atomic_exchange_n(&settings_seen,0,__ATOMIC_ACQ_REL);}





__attribute__((visibility("default"))) int Java_local_simcity_offline_UiCleaner_pollShopVisible(void*e,void*c){return __atomic_exchange_n(&shop_seen,0,__ATOMIC_ACQ_REL);}
__attribute__((visibility("default"))) int Java_local_simcity_offline_UiCleaner_addCurrency(void*e,void*c,int type){if(type<0||type>1||!gamebase)return 0;__atomic_fetch_add(pending+type,1,__ATOMIC_RELEASE);return 1;}

__attribute__((visibility("default"))) int Java_local_simcity_offline_UiCleaner_currencyAppliedCount(void*e,void*c,int type){if(type<0||type>1)return 0;return __atomic_load_n(applied+type,__ATOMIC_ACQUIRE);}

