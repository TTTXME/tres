bool scameraHasCameraDiffusion(const RenderParams& p,int w,int h){
  if(!p.cameraDiffusionEnabled||p.cameraDiffusionStrength<=0||p.cameraDiffusionSpatialScale<=0
      ||!envFlagEnabledOrDefault("SPEKTRAFILM_VULKAN_DIFFUSION_PASS",true))return false;
  VulkanDiffusionInfo info{};
  const float frame=p.filmFormatLongEdgeMmOverride>0?p.filmFormatLongEdgeMmOverride:filmFormatLongEdgeMm(p.filmFormat);
  makeDiffusionComponents({p.cameraDiffusionFamily,p.cameraDiffusionStrength,p.cameraDiffusionSpatialScale,
      p.cameraDiffusionHaloWarmth,p.cameraDiffusionCoreIntensity,p.cameraDiffusionCoreSize,p.cameraDiffusionHaloIntensity,
      p.cameraDiffusionHaloSize,p.cameraDiffusionBloomIntensity,p.cameraDiffusionBloomSize},
      frame*1000.0f/std::max(w,h)/resolvedEnlargerScale(p),info,diffusionClusterSigmaRatio);
  return info.componentCount>0;
}
// SCAMERA tiling of the upstream GPL-3.0 diffusion operations.
uint32_t scameraStageOverlap(const RenderParams& p,int width,int height,uint32_t fallback){
  const float frame=p.filmFormatLongEdgeMmOverride>0?p.filmFormatLongEdgeMmOverride:filmFormatLongEdgeMm(p.filmFormat);
  const float pixel=std::max(frame*1000.0f/std::max(width,height)/resolvedEnlargerScale(p),1.0e-6f);
  auto radius=[](float sigma){return uint32_t(std::min(256.0f,std::ceil(3.0f*std::max(0.0f,sigma))));};
  if(scameraStage==1){
    const uint32_t scatter=p.scatterAmount>0?radius(9.7f*2.7684f*p.scatterScale/pixel):0;
    const float* first=staticFilm.curves?staticFilm.curves->halationFirstSigmaUm:nullptr;
    const float sigma=first?std::max({first[0],first[1],first[2]}):std::max({p.halationFirstSigmaUmR,p.halationFirstSigmaUmG,p.halationFirstSigmaUmB});
    return scatter+(p.halationAmount>0?radius(sigma*p.halationScale*std::sqrt(3.0f)/pixel):0);
  }
  if(scameraStage==3)return p.grainEnabled?128:0;
  if(scameraStage==4&&p.scannerEnabled&&!p.printDiffusionEnabled&&p.glarePercent<=0)
    return radius(scannerSigmaUmFromMtf50(p.scannerMtf50LpMm)/pixel)
        +(p.scannerUnsharpAmount>0?radius(p.scannerUnsharpRadiusUm/pixel):0);
  return fallback;
}
struct ScameraDiffusionOp { uint32_t op,component,packed,width,height; uint32_t lowOffset=0; int activeMode=-1; };
std::array<ScratchBuffer,7> scameraDiffusionScratch;
void scameraReleaseDiffusionScratch(){for(auto& b:scameraDiffusionScratch)destroyScratchBuffer(b);}
static std::vector<float> scameraWindow(const float* data,int stride,int x,int y,int w,int h){
  std::vector<float> out(size_t(w)*h*4);
  for(int row=0;row<h;row++)std::memcpy(out.data()+size_t(row)*w*4,data+(size_t(y+row)*stride+x)*4,size_t(w)*16);
  return out;
}
static void scameraStoreWindow(float* target,int stride,int x,int y,int w,int h,
    const float* data,int sourceWidth,int sourceX,int sourceY){
  for(int row=0;row<h;row++)std::memcpy(target+(size_t(y+row)*stride+x)*4,
      data+(size_t(sourceY+row)*sourceWidth+sourceX)*4,size_t(w)*16);
}
int scameraDiffusionTile(uint32_t halo,uint32_t planeBytes){
  const uint64_t pixels=std::min<uint64_t>(physicalDeviceProperties.limits.maxStorageBufferRange/planeBytes,
      scratchBudgetBytes>(8ull<<20)?(scratchBudgetBytes-(8ull<<20))/112:0);
  const uint32_t edge=uint32_t(std::sqrt(double(pixels)));
  if(edge<=2*halo+32){lastError="Diffusion tile exceeds the memory budget";return 0;}
  return int((edge-2*halo)/32*32);
}
bool scameraDiffusionSubmit(VulkanCorePushConstants push,const std::array<size_t,7>& sizes,
    const std::array<const float*,7>& inputs,const std::vector<ScameraDiffusionOp>& operations,
    int readIndex,const float*& result){
  if(!ensureCoreFrameResources())return false;
  for(int i=0;i<7;i++){
    const size_t n=std::max<size_t>(sizes[i],16);
    if(i==1||i==5){if(!ensurePrivateScratchBuffer(scameraDiffusionScratch[i],n,"diffusion tile temp"))return false;}
    else if(!ensureSharedScratchBuffer(scameraDiffusionScratch[i],n,"diffusion tile shared"))return false;
    if(inputs[i]){
      std::memcpy(scameraDiffusionScratch[i].mapped,inputs[i],sizes[i]);
      if(!flushMappedScratchBuffer(scameraDiffusionScratch[i],sizes[i],"diffusion tile upload"))return false;
    }
  }
  diagnostics.transientCachedBytes=std::max(diagnostics.transientCachedBytes,transientAllocationBytes());
  std::array<VkDescriptorBufferInfo,9> buffers{};
  std::array<VkWriteDescriptorSet,9> writes{};
  for(int i=0;i<9;i++){
    const auto& buffer=i<7?scameraDiffusionScratch[i]:i==7?coreFrame.cameraDiffusionInfo:coreFrame.cameraDiffusionComponents;
    buffers[i].buffer=buffer.buffer;buffers[i].range=i<7?std::max<size_t>(sizes[i],16):buffer.capacity;
    writes[i].sType=VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[i].dstSet=coreFrame.diffusionDescriptorSets[0];writes[i].dstBinding=i<7?i:i==7?27:28;
    writes[i].descriptorCount=1;writes[i].descriptorType=VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;writes[i].pBufferInfo=&buffers[i];
  }
  vkUpdateDescriptorSets(device,uint32_t(writes.size()),writes.data(),0,nullptr);
  VkCommandBuffer command=coreFrame.commandBuffer;
  VkResult rc=vkResetCommandBuffer(command,0);
  if(rc!=VK_SUCCESS){lastError=vkError("diffusion reset",rc);return false;}
  VkCommandBufferBeginInfo begin{};begin.sType=VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;begin.flags=VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
  rc=vkBeginCommandBuffer(command,&begin);
  if(rc!=VK_SUCCESS){lastError=vkError("diffusion begin",rc);return false;}
  auto barrier=[&](VkPipelineStageFlags src,VkPipelineStageFlags dst,VkAccessFlags from,VkAccessFlags to){
    VkMemoryBarrier b{};b.sType=VK_STRUCTURE_TYPE_MEMORY_BARRIER;b.srcAccessMask=from;b.dstAccessMask=to;
    vkCmdPipelineBarrier(command,src,dst,0,1,&b,0,nullptr,0,nullptr);
  };
  barrier(VK_PIPELINE_STAGE_HOST_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_HOST_WRITE_BIT,VK_ACCESS_SHADER_READ_BIT);
  const auto centerX=push.activeOriginX,centerY=push.activeOriginY,centerWidth=push.activeWidth,centerHeight=push.activeHeight;
  for(const auto& op:operations){
    if(op.op==UINT32_MAX){
      barrier(VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_SHADER_WRITE_BIT,VK_ACCESS_TRANSFER_READ_BIT);
      VkBufferCopy copy{};copy.size=sizes[0];
      vkCmdCopyBuffer(command,scameraDiffusionScratch[3].buffer,scameraDiffusionScratch[0].buffer,1,&copy);
      barrier(VK_PIPELINE_STAGE_TRANSFER_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_TRANSFER_WRITE_BIT,VK_ACCESS_SHADER_READ_BIT);
      continue;
    }
    push.rgbToRawMethod=-1;push.filmPushPullMode=int32_t(op.lowOffset);
    push.activeOriginX=op.activeMode<0?push.activeOriginX:op.activeMode?centerX:0;
    push.activeOriginY=op.activeMode<0?push.activeOriginY:op.activeMode?centerY:0;
    push.activeWidth=op.activeMode<0?push.activeWidth:op.activeMode?centerWidth:push.width;
    push.activeHeight=op.activeMode<0?push.activeHeight:op.activeMode?centerHeight:push.height;
    push._pad0=op.op;push._pad1=op.component;push._pad2=op.packed;
    vkCmdBindPipeline(command,VK_PIPELINE_BIND_POINT_COMPUTE,diffusionPipeline);
    vkCmdBindDescriptorSets(command,VK_PIPELINE_BIND_POINT_COMPUTE,corePipelineLayout,0,1,&coreFrame.diffusionDescriptorSets[0],0,nullptr);
    vkCmdPushConstants(command,corePipelineLayout,VK_SHADER_STAGE_COMPUTE_BIT,0,sizeof(push),&push);
    vkCmdDispatch(command,(op.width+31)/32,(op.height+7)/8,1);
    barrier(VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT);
    diagnostics.passCount++;
  }
  barrier(VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_PIPELINE_STAGE_HOST_BIT,VK_ACCESS_SHADER_WRITE_BIT,VK_ACCESS_HOST_READ_BIT);
  rc=vkEndCommandBuffer(command);
  if(rc==VK_SUCCESS)rc=vkResetFences(device,1,&coreFrame.fence);
  VkSubmitInfo submit{};submit.sType=VK_STRUCTURE_TYPE_SUBMIT_INFO;submit.commandBufferCount=1;submit.pCommandBuffers=&command;
  if(rc==VK_SUCCESS)rc=backend->submit(queueIndex,submit,coreFrame.fence);
  if(rc==VK_SUCCESS)rc=vkWaitForFences(device,1,&coreFrame.fence,VK_TRUE,UINT64_MAX);
  if(rc!=VK_SUCCESS){lastError=vkError("diffusion submit/wait",rc);return false;}
  if(!invalidateMappedScratchBuffer(scameraDiffusionScratch[readIndex],sizes[readIndex],"diffusion tile readback"))return false;
  result=static_cast<const float*>(scameraDiffusionScratch[readIndex].mapped);
  diagnostics.tileCount++;return true;
}
bool renderGlobalCameraDiffusionLegacy(const ImageView& source,const MutableImageView& destination,const RenderParams& p,bool rawOutput){
  const int w=source.width,h=source.height;
  if(source.bytesPerComponent!=4||destination.bytesPerComponent!=4||source.rowBytes!=w*16||destination.rowBytes!=w*16){
    lastError="Global diffusion requires dense float RGBA milestones";return false;
  }
  VulkanDiffusionInfo info{};
  const float frameMm=p.filmFormatLongEdgeMmOverride>0?p.filmFormatLongEdgeMmOverride:filmFormatLongEdgeMm(p.filmFormat);
  const float pixelUm=frameMm*1000.0f/float(std::max(w,h))/resolvedEnlargerScale(p);
  const auto components=makeDiffusionComponents({p.cameraDiffusionFamily,p.cameraDiffusionStrength,p.cameraDiffusionSpatialScale,
      p.cameraDiffusionHaloWarmth,p.cameraDiffusionCoreIntensity,p.cameraDiffusionCoreSize,p.cameraDiffusionHaloIntensity,
      p.cameraDiffusionHaloSize,p.cameraDiffusionBloomIntensity,p.cameraDiffusionBloomSize},pixelUm,info,diffusionClusterSigmaRatio);
  if(components.empty()){lastError="Diffusion has no active components";return false;}
  const float* raw=static_cast<const float*>(source.data);float* accum=static_cast<float*>(destination.data);
  const size_t pixels=size_t(w)*h;
  std::memset(accum,0,pixels*16);for(size_t i=0;i<pixels;i++)accum[i*4+3]=raw[i*4+3];
  if(!uploadScratchBuffer(coreFrame.cameraDiffusionInfo,&info,sizeof(info),"global diffusion info")
      ||!uploadScratchBuffer(coreFrame.cameraDiffusionComponents,components.data(),components.size()*sizeof(VulkanDiffusionComponent),"global diffusion components"))return false;
  VulkanCorePushConstants base{};base.fullWidth=w;base.fullHeight=h;
  auto pushFor=[&](int width,int height,int x,int y){auto push=base;push.width=width;push.height=height;push.tileOriginX=x;push.tileOriginY=y;return push;};
  auto pack=[](uint32_t n,uint32_t scale){return (scale<<16)|n;};
  std::vector<float> reduced;uint32_t cachedScale=0;int lw=0,lh=0;
  for(uint32_t component=0;component<components.size();){
    const uint32_t scale=diffusionDownsampleScaleForSigma(blurDownsample,components[component].sigmaPx);
    uint32_t n=1;while(component+n<components.size()&&n<diffusionGroupSize
        &&diffusionDownsampleScaleForSigma(blurDownsample,components[component+n].sigmaPx)==scale)n++;
    float sigma=0;for(uint32_t i=0;i<n;i++)sigma=std::max(sigma,components[component+i].sigmaPx);
    const uint32_t radius=std::min<uint32_t>(uint32_t(std::ceil(3*sigma/scale)),256);
    scameraReleaseDiffusionScratch();
    if(scale==1){
      const int tile=scameraDiffusionTile(radius,16*n);if(tile<=0)return false;
      for(int y=0;y<h;y+=tile)for(int x=0;x<w;x+=tile){
        const int cw=std::min(tile,w-x),ch=std::min(tile,h-y),x0=std::max(0,x-int(radius)),y0=std::max(0,y-int(radius));
        const int ww=std::min(w,x+cw+int(radius))-x0,hh=std::min(h,y+ch+int(radius))-y0;
        auto src=scameraWindow(raw,w,x0,y0,ww,hh),before=scameraWindow(accum,w,x0,y0,ww,hh);
        const size_t bytes=size_t(ww)*hh*16;
        std::array<size_t,7> sizes{bytes,bytes*n,bytes,16,16,16,16};
        std::array<const float*,7> inputs{};inputs[0]=src.data();inputs[2]=before.data();
        std::vector<ScameraDiffusionOp> ops{{n==1?kDiffusionOpBlurX:kDiffusionOpGroupBlurX,component,n,uint32_t(ww),uint32_t(hh)},
            {n==1?kDiffusionOpBlurYAccumulate:kDiffusionOpGroupBlurYAccumulate,component,n,uint32_t(ww),uint32_t(hh)}};
        const float* output=nullptr;
        if(!scameraDiffusionSubmit(pushFor(ww,hh,x0,y0),sizes,inputs,ops,2,output))return false;
        scameraStoreWindow(accum,w,x,y,cw,ch,output,ww,x-x0,y-y0);
      }
    }else{
      if(cachedScale!=scale){
        cachedScale=scale;lw=(w+scale-1)/scale;lh=(h+scale-1)/scale;reduced.assign(size_t(lw)*lh*4,0);
        for(int y=0;y<lh;y++)for(int x=0;x<lw;x++){
          float value[4]={0,0,0,0};
          for(uint32_t yy=0;yy<scale;yy++)for(uint32_t xx=0;xx<scale;xx++){
            const float* pixel=raw+(size_t(std::min(h-1,int(y*scale+yy)))*w+std::min(w-1,int(x*scale+xx)))*4;
            for(int c=0;c<4;c++)value[c]+=pixel[c];
          }
          for(int c=0;c<4;c++)reduced[(size_t(y)*lw+x)*4+c]=value[c]/float(scale*scale);
        }
      }
      std::vector<float> blurred(size_t(lw)*lh*n*4);
      const int lowTile=scameraDiffusionTile(radius,16*n);if(lowTile<=0)return false;
      for(int y=0;y<lh;y+=lowTile)for(int x=0;x<lw;x+=lowTile){
        const int cw=std::min(lowTile,lw-x),ch=std::min(lowTile,lh-y),x0=std::max(0,x-int(radius)),y0=std::max(0,y-int(radius));
        const int ww=std::min(lw,x+cw+int(radius))-x0,hh=std::min(lh,y+ch+int(radius))-y0;
        auto src=scameraWindow(reduced.data(),lw,x0,y0,ww,hh);const size_t bytes=size_t(ww)*hh*16;
        std::array<size_t,7> sizes{16,16,16,16,bytes,bytes*n,bytes*n};
        std::array<const float*,7> inputs{};inputs[4]=src.data();
        std::vector<ScameraDiffusionOp> ops{{n==1?kDiffusionOpDownsampleBlurX:kDiffusionOpDownsampleGroupBlurX,component,pack(n,scale),uint32_t(ww),uint32_t(hh)},
            {n==1?kDiffusionOpDownsampleBlurY:kDiffusionOpDownsampleGroupBlurY,component,pack(n,scale),uint32_t(ww),uint32_t(hh)}};
        const float* output=nullptr;
        if(!scameraDiffusionSubmit(pushFor(ww*scale,hh*scale,x0*scale,y0*scale),sizes,inputs,ops,6,output))return false;
        for(uint32_t plane=0;plane<n;plane++)scameraStoreWindow(blurred.data()+size_t(plane)*lw*lh*4,lw,x,y,cw,ch,
            output+size_t(plane)*ww*hh*4,ww,x-x0,y-y0);
      }
      scameraReleaseDiffusionScratch();
      const int tile=scameraDiffusionTile(2*scale,16*n);if(tile<=0)return false;
      for(int y=0;y<h;y+=tile)for(int x=0;x<w;x+=tile){
        const int cw=std::min(tile,w-x),ch=std::min(tile,h-y),x0=std::max(0,x-int(2*scale)),y0=std::max(0,y-int(2*scale));
        const int ww=std::min(w,x+cw+int(2*scale))-x0,hh=std::min(h,y+ch+int(2*scale))-y0;
        const int lx=x0/scale,ly=y0/scale,wwl=(x0+ww+scale-1)/scale-lx,hhl=(y0+hh+scale-1)/scale-ly;
        auto src=scameraWindow(raw,w,x0,y0,ww,hh),before=scameraWindow(accum,w,x0,y0,ww,hh);
        std::vector<float> low(size_t(wwl)*hhl*n*4);
        for(uint32_t plane=0;plane<n;plane++){
          auto values=scameraWindow(blurred.data()+size_t(plane)*lw*lh*4,lw,lx,ly,wwl,hhl);
          std::memcpy(low.data()+size_t(plane)*wwl*hhl*4,values.data(),values.size()*4);
        }
        const size_t bytes=size_t(ww)*hh*16;
        std::array<size_t,7> sizes{bytes,16,bytes,16,16,16,low.size()*4};
        std::array<const float*,7> inputs{};inputs[0]=src.data();inputs[2]=before.data();inputs[6]=low.data();
        auto push=pushFor(ww,hh,x0,y0);push.activeOriginX=x-x0;push.activeOriginY=y-y0;push.activeWidth=cw;push.activeHeight=ch;
        std::vector<ScameraDiffusionOp> ops{{n==1?kDiffusionOpDownsampleUpsampleAccumulate:kDiffusionOpDownsampleGroupUpsampleAccumulate,component,pack(n,scale),uint32_t(cw),uint32_t(ch)}};
        const float* output=nullptr;if(!scameraDiffusionSubmit(push,sizes,inputs,ops,2,output))return false;
        scameraStoreWindow(accum,w,x,y,cw,ch,output,ww,x-x0,y-y0);
      }
    }
    component+=n;
  }
  scameraReleaseDiffusionScratch();
  const int tile=scameraDiffusionTile(0,16);if(tile<=0)return false;
  for(int y=0;y<h;y+=tile)for(int x=0;x<w;x+=tile){
    const int ww=std::min(tile,w-x),hh=std::min(tile,h-y);const size_t bytes=size_t(ww)*hh*16;
    auto src=scameraWindow(raw,w,x,y,ww,hh),before=scameraWindow(accum,w,x,y,ww,hh);
    std::array<size_t,7> sizes{bytes,16,bytes,bytes,16,16,16};
    std::array<const float*,7> inputs{};inputs[0]=src.data();inputs[2]=before.data();
    std::vector<ScameraDiffusionOp> ops{{kDiffusionOpResolve,0,0,uint32_t(ww),uint32_t(hh)}};
    if(!rawOutput){ops.push_back({UINT32_MAX,0,0,0,0});ops.push_back({kDiffusionOpRawToLog,0,0,uint32_t(ww),uint32_t(hh)});}
    const float* output=nullptr;if(!scameraDiffusionSubmit(pushFor(ww,hh,x,y),sizes,inputs,ops,3,output))return false;
    scameraStoreWindow(accum,w,x,y,ww,hh,output,ww,0,0);
  }
  scameraReleaseDiffusionScratch();return true;
}

bool renderGlobalCameraDiffusion(const ImageView& source,const MutableImageView& destination,const RenderParams& p,bool rawOutput){
  if(std::getenv("SPEKTRAFILM_SCAMERA_BATCH_OFF"))return renderGlobalCameraDiffusionLegacy(source,destination,p,rawOutput);
  const int w=source.width,h=source.height;
  VulkanDiffusionInfo info{};
  const float frameMm=p.filmFormatLongEdgeMmOverride>0?p.filmFormatLongEdgeMmOverride:filmFormatLongEdgeMm(p.filmFormat);
  const float pixelUm=frameMm*1000.0f/float(std::max(w,h))/resolvedEnlargerScale(p);
  const auto components=makeDiffusionComponents({p.cameraDiffusionFamily,p.cameraDiffusionStrength,p.cameraDiffusionSpatialScale,
      p.cameraDiffusionHaloWarmth,p.cameraDiffusionCoreIntensity,p.cameraDiffusionCoreSize,p.cameraDiffusionHaloIntensity,
      p.cameraDiffusionHaloSize,p.cameraDiffusionBloomIntensity,p.cameraDiffusionBloomSize},pixelUm,info,diffusionClusterSigmaRatio);
  if(components.empty())return false;
  struct Group{uint32_t first,n,scale,radius;int lw,lh;std::vector<float> image;};
  std::vector<Group> groups;uint64_t cachedBytes=0,reducedBytes=0;uint32_t rawHalo=0,rawPlanes=1;
  for(uint32_t first=0;first<components.size();){
    const uint32_t scale=diffusionDownsampleScaleForSigma(blurDownsample,components[first].sigmaPx);
    uint32_t n=1;while(first+n<components.size()&&n<diffusionGroupSize
        &&diffusionDownsampleScaleForSigma(blurDownsample,components[first+n].sigmaPx)==scale)n++;
    float sigma=0;for(uint32_t j=0;j<n;j++)sigma=std::max(sigma,components[first+j].sigmaPx);
    uint32_t radius=std::min(uint32_t(std::ceil(3*sigma/scale)),256u);
    Group g{first,n,scale,radius,(w+int(scale)-1)/int(scale),(h+int(scale)-1)/int(scale),{}};
    if(scale>1){cachedBytes+=uint64_t(g.lw)*g.lh*n*16;reducedBytes=std::max(reducedBytes,uint64_t(g.lw)*g.lh*16);}
    else{rawHalo=std::max(rawHalo,radius);rawPlanes=std::max(rawPlanes,n);}
    groups.push_back(std::move(g));first+=n;
  }
  uint64_t extra=cachedBytes+reducedBytes;const uint64_t alreadyReserved=uint64_t(w)*h*12;
  extra=extra>alreadyReserved?extra-alreadyReserved:0;
  if(scratchBudgetBytes<=extra+(64ull<<20)){lastError="Global diffusion cache exceeds the film budget";return false;}
  struct Budget{Impl* self;uint64_t old;~Budget(){self->scratchBudgetBytes=old;}} budget{this,scratchBudgetBytes};
  scratchBudgetBytes-=extra;
  const float* raw=static_cast<const float*>(source.data);float* resultImage=static_cast<float*>(destination.data);
  if(!uploadScratchBuffer(coreFrame.cameraDiffusionInfo,&info,sizeof(info),"global diffusion info")
      ||!uploadScratchBuffer(coreFrame.cameraDiffusionComponents,components.data(),components.size()*sizeof(VulkanDiffusionComponent),"global diffusion components"))return false;
  VulkanCorePushConstants base{};base.fullWidth=w;base.fullHeight=h;
  auto pushFor=[&](int ww,int hh,int x,int y){auto push=base;push.width=ww;push.height=hh;push.tileOriginX=x;push.tileOriginY=y;return push;};
  auto pack=[](uint32_t n,uint32_t scale){return (scale<<16)|n;};
  std::vector<float> reduced;uint32_t currentScale=0;
  for(auto& g:groups){
    if(g.scale==1)continue;
    if(currentScale!=g.scale){
      currentScale=g.scale;reduced.assign(size_t(g.lw)*g.lh*4,0);
      for(int y=0;y<g.lh;y++)for(int x=0;x<g.lw;x++){
        float value[4]={0,0,0,0};
        for(uint32_t yy=0;yy<g.scale;yy++)for(uint32_t xx=0;xx<g.scale;xx++){
          const float* pixel=raw+(size_t(std::min(h-1,int(y*g.scale+yy)))*w+std::min(w-1,int(x*g.scale+xx)))*4;
          for(int c=0;c<4;c++)value[c]+=pixel[c];
        }
        for(int c=0;c<4;c++)reduced[(size_t(y)*g.lw+x)*4+c]=value[c]/float(g.scale*g.scale);
      }
    }
    g.image.resize(size_t(g.lw)*g.lh*g.n*4);scameraReleaseDiffusionScratch();
    const int tile=scameraDiffusionTile(g.radius,16*g.n);if(tile<=0)return false;
    for(int y=0;y<g.lh;y+=tile)for(int x=0;x<g.lw;x+=tile){
      const int cw=std::min(tile,g.lw-x),ch=std::min(tile,g.lh-y),x0=std::max(0,x-int(g.radius)),y0=std::max(0,y-int(g.radius));
      const int ww=std::min(g.lw,x+cw+int(g.radius))-x0,hh=std::min(g.lh,y+ch+int(g.radius))-y0;
      auto src=scameraWindow(reduced.data(),g.lw,x0,y0,ww,hh);const size_t bytes=size_t(ww)*hh*16;
      std::array<size_t,7> sizes{16,16,16,16,bytes,bytes*g.n,bytes*g.n};std::array<const float*,7> inputs{};inputs[4]=src.data();
      std::vector<ScameraDiffusionOp> ops{{g.n==1?kDiffusionOpDownsampleBlurX:kDiffusionOpDownsampleGroupBlurX,g.first,pack(g.n,g.scale),uint32_t(ww),uint32_t(hh),0,0},
          {g.n==1?kDiffusionOpDownsampleBlurY:kDiffusionOpDownsampleGroupBlurY,g.first,pack(g.n,g.scale),uint32_t(ww),uint32_t(hh),0,0}};
      const float* output=nullptr;
      if(!scameraDiffusionSubmit(pushFor(ww*g.scale,hh*g.scale,x0*g.scale,y0*g.scale),sizes,inputs,ops,6,output))return false;
      for(uint32_t plane=0;plane<g.n;plane++)scameraStoreWindow(g.image.data()+size_t(plane)*g.lw*g.lh*4,g.lw,x,y,cw,ch,
          output+size_t(plane)*ww*hh*4,ww,x-x0,y-y0);
    }
  }
  std::vector<float>().swap(reduced);scameraReleaseDiffusionScratch();
  for(const auto& g:groups)if(g.scale>1)rawHalo=std::max(rawHalo,2*g.scale);
  double lowBytes=0;for(const auto& g:groups)if(g.scale>1)lowBytes+=double(16*g.n)/(g.scale*g.scale);
  const uint32_t maxPlane=std::max(uint32_t(16*rawPlanes),uint32_t(std::ceil(lowBytes*1.2+16)));
  int tile=scameraDiffusionTile(rawHalo,maxPlane);if(tile<=0)return false;
  const uint64_t rawPixels=scratchBudgetBytes>(8ull<<20)?(scratchBudgetBytes-(8ull<<20))/uint64_t(144+2*std::ceil(lowBytes)):0;
  const int rawEdge=int(std::sqrt(double(rawPixels)));
  if(rawEdge<=int(2*rawHalo+32)){lastError="Diffusion tile staging exceeds the memory budget";return false;}
  tile=std::min(tile,(rawEdge-int(2*rawHalo))/32*32);
  for(int y=0;y<h;y+=tile)for(int x=0;x<w;x+=tile){
    const int cw=std::min(tile,w-x),ch=std::min(tile,h-y),x0=std::max(0,x-int(rawHalo)),y0=std::max(0,y-int(rawHalo));
    const int ww=std::min(w,x+cw+int(rawHalo))-x0,hh=std::min(h,y+ch+int(rawHalo))-y0;const size_t bytes=size_t(ww)*hh*16;
    auto src=scameraWindow(raw,w,x0,y0,ww,hh);std::vector<float> low;std::vector<ScameraDiffusionOp> ops{{kDiffusionOpClear,0,0,uint32_t(ww),uint32_t(hh),0,0}};
    for(const auto& g:groups){
      if(g.scale==1){
        ops.push_back({g.n==1?kDiffusionOpBlurX:kDiffusionOpGroupBlurX,g.first,g.n,uint32_t(ww),uint32_t(hh),0,0});
        ops.push_back({g.n==1?kDiffusionOpBlurYAccumulate:kDiffusionOpGroupBlurYAccumulate,g.first,g.n,uint32_t(ww),uint32_t(hh),0,0});
      }else{
        const int lx=x0/g.scale,ly=y0/g.scale,wwl=(x0+ww+g.scale-1)/g.scale-lx,hhl=(y0+hh+g.scale-1)/g.scale-ly;
        const uint32_t offset=uint32_t(low.size()/4);const size_t old=low.size();low.resize(old+size_t(wwl)*hhl*g.n*4);
        for(uint32_t plane=0;plane<g.n;plane++)scameraStoreWindow(low.data()+old+size_t(plane)*wwl*hhl*4,wwl,0,0,wwl,hhl,
            g.image.data()+size_t(plane)*g.lw*g.lh*4,g.lw,lx,ly);
        ops.push_back({g.n==1?kDiffusionOpDownsampleUpsampleAccumulate:kDiffusionOpDownsampleGroupUpsampleAccumulate,g.first,pack(g.n,g.scale),uint32_t(cw),uint32_t(ch),offset,1});
      }
    }
    ops.push_back({kDiffusionOpResolve,0,0,uint32_t(cw),uint32_t(ch),0,1});
    if(!rawOutput){ops.push_back({UINT32_MAX,0,0,0,0});ops.push_back({kDiffusionOpRawToLog,0,0,uint32_t(cw),uint32_t(ch),0,1});}
    std::array<size_t,7> sizes{bytes,bytes*rawPlanes,bytes,bytes,16,16,low.size()*4};std::array<const float*,7> inputs{};inputs[0]=src.data();inputs[6]=low.empty()?nullptr:low.data();
    auto push=pushFor(ww,hh,x0,y0);push.activeOriginX=x-x0;push.activeOriginY=y-y0;push.activeWidth=cw;push.activeHeight=ch;
    const float* output=nullptr;if(!scameraDiffusionSubmit(push,sizes,inputs,ops,3,output))return false;
    scameraStoreWindow(resultImage,w,x,y,cw,ch,output,ww,x-x0,y-y0);
  }
  scameraReleaseDiffusionScratch();return true;
}
