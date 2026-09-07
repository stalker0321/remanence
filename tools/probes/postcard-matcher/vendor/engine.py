"""Sparse visual-word retrieval followed by local geometric verification on CPU."""
import argparse,json,time
from functools import lru_cache
from pathlib import Path
import cv2
import numpy as np
from scipy import sparse
from vision import auto_crop,decode,features,root,verify

DEFAULT_THRESHOLD=12.0
DEFAULT_MARGIN=1.6

class Vocabulary:
    def __init__(self,centers):
        self.centers=centers.astype(np.float32)
        cv2.setRNGSeed(42)
        self.index=cv2.flann_Index(self.centers,dict(algorithm=1,trees=4))
    def histogram(self,descriptors,query=False):
        if not len(descriptors):return sparse.csr_matrix((1,len(self.centers)),dtype=np.float32)
        words,distances=self.index.knnSearch(root(descriptors),2,params=dict(checks=48))
        # Soft multiple assignment reduces vocabulary boundary sensitivity.
        weights=np.exp(-distances.ravel()/.05) if query else None
        counts=np.bincount(words.ravel(),weights=weights,minlength=len(self.centers)).astype(np.float32)
        return sparse.csr_matrix(np.sqrt(counts)[None,:])

def normalized(matrix):
    norms=np.sqrt(np.asarray(matrix.multiply(matrix).sum(axis=1)).ravel())
    return sparse.diags(1/np.maximum(norms,1e-12)).dot(matrix).tocsr()

def build(enrollment,output,words=1024):
    started=time.perf_counter();output=Path(output)
    if (output/'metadata.json').exists():raise ValueError('Index exists; choose a new output directory')
    rows=json.loads(Path(enrollment).read_text())
    if any(set(row)!={'card_id','path'} for row in rows):raise ValueError('Enrollment accepts only card_id and raw image path; no coordinates or annotations')
    if not rows or len({r['card_id'] for r in rows})!=len(rows):raise ValueError('Empty or duplicate IDs')
    if any(r['card_id']=='023' for r in rows):raise ValueError('023 is reserved as unknown in this experiment')
    output.mkdir(parents=True,exist_ok=True)
    metadata=[]
    for i,row in enumerate(rows):
        im=decode(row['path'],max_side=960);im,detected=auto_crop(im);xy,d=features(im)
        if len(d)<12:raise ValueError(f"Too few features for {row['card_id']}: {len(d)}")
        np.savez_compressed(output/f'{i}.npz',xy=xy,descriptors=d,shape=im.shape[:2])
        # The served reference is the automatically cropped T01. The original
        # photograph remains in the dataset and is never overwritten.
        cv2.imwrite(str(output/f'{i}.jpg'), im, [cv2.IMWRITE_JPEG_QUALITY, 92])
        metadata.append(dict(card_id=row['card_id'], asset=f'{i}.jpg',
                             features=len(d), crop_detected=bool(detected),
                             crop_shape=list(im.shape[:2])))
    # Uniform bounded training sample: descriptors for the full catalogue stay on disk.
    total=sum(r['features'] for r in metadata)
    rng=np.random.default_rng(42)
    chosen=np.sort(rng.choice(total,min(total,100000),replace=False))
    samples=[];offset=0
    for i,row in enumerate(metadata):
        local=chosen[(chosen>=offset)&(chosen<offset+row['features'])]-offset
        if len(local):
            with np.load(output/f'{i}.npz') as z:samples.append(z['descriptors'][local])
        offset+=row['features']
    train=root(np.concatenate(samples))
    cv2.setRNGSeed(42)
    _,_,centers=cv2.kmeans(train,min(words,len(train)),None,(cv2.TERM_CRITERIA_MAX_ITER|cv2.TERM_CRITERIA_EPS,20,.001),1,cv2.KMEANS_PP_CENTERS)
    vocab=Vocabulary(centers)
    histograms=[]
    for i in range(len(metadata)):
        with np.load(output/f'{i}.npz') as z:histograms.append(vocab.histogram(z['descriptors']))
    hist=sparse.vstack(histograms).tocsr()
    df=np.asarray((hist>0).sum(axis=0)).ravel()
    idf=(np.log((len(rows)+1)/(df+1))+1).astype(np.float32)
    matrix=normalized(hist.multiply(idf))
    sparse.save_npz(output/'retrieval.npz',matrix)
    np.save(output/'centers.npy',centers);np.save(output/'idf.npy',idf)
    (output/'metadata.json').write_text(json.dumps(dict(version=3,preprocessing='auto_grabcut_or_full_frame_grid_sift',cards=metadata,words=len(centers),threshold=DEFAULT_THRESHOLD,margin=DEFAULT_MARGIN,build_seconds=time.perf_counter()-started),indent=2))
    print(json.dumps(dict(cards=len(rows),descriptors=len(train),build_seconds=round(time.perf_counter()-started,2))))

class Matcher:
    def __init__(self,index,top_k=8):
        self.path=Path(index);self.meta=json.loads((self.path/'metadata.json').read_text())
        if self.meta.get('preprocessing')!='auto_grabcut_or_full_frame_grid_sift':raise ValueError('Index preprocessing mismatch; rebuild from raw images')
        self.cards=self.meta['cards'];self.vocab=Vocabulary(np.load(self.path/'centers.npy'))
        self.idf=np.load(self.path/'idf.npy');self.matrix=sparse.load_npz(self.path/'retrieval.npz')
        self.top_k=min(top_k,len(self.cards))
        self.threshold=self.meta['threshold'];self.margin=self.meta['margin']
    @lru_cache(maxsize=32)
    def reference(self,i):
        with np.load(self.path/f'{i}.npz') as data:return {key:data[key] for key in data.files}
    def extract_timed(self, source):
        started = time.perf_counter()
        im = decode(source, max_side=960)
        decoded_ms = (time.perf_counter() - started) * 1000
        crop_started = time.perf_counter()
        im, detected = auto_crop(im)
        crop_ms = (time.perf_counter() - crop_started) * 1000
        feature_started = time.perf_counter()
        xy, d = features(im)
        feature_ms = (time.perf_counter() - feature_started) * 1000
        return xy, d, im.shape[:2], dict(decode=decoded_ms, crop=crop_ms,
                                         features=feature_ms, crop_detected=detected)

    def extract(self,source):
        xy, d, shape, _ = self.extract_timed(source)
        return xy, d, shape
    def match_features(self,xy,d,shape,exclude=()):
        start=time.perf_counter()
        hist=normalized(self.vocab.histogram(d,query=True).multiply(self.idf))
        scores=np.asarray(self.matrix@hist.toarray().ravel()).ravel()
        eligible=np.array([i for i,c in enumerate(self.cards) if c['card_id'] not in exclude],dtype=int)
        if not len(eligible):raise ValueError('No eligible references')
        shortlist=eligible[np.argsort(-scores[eligible],kind='stable')[:self.top_k]]
        retrieval_ms=(time.perf_counter()-start)*1000
        qd=root(d);results=[]
        cv2.setRNGSeed(42)
        qindex=cv2.flann_Index(qd,dict(algorithm=1,trees=4)) if len(qd)>=4 else None
        for idx in shortlist:
            idx=int(idx);r=verify(xy,qd,self.reference(idx),qindex)
            # Reject extrapolations far outside the uploaded photograph.
            if r['polygon']:
                p=np.array(r['polygon']);h,w=shape
                if (p[:,0]<-.25*w).any() or (p[:,0]>1.25*w).any() or (p[:,1]<-.25*h).any() or (p[:,1]>1.25*h).any():r['score']=0.
            results.append(dict(card_id=self.cards[idx]['card_id'],reference_asset=self.cards[idx]['asset'],retrieval_score=float(scores[idx]),**r))
        results.sort(key=lambda r:r['score'],reverse=True)
        best=results[0];runner=results[1]['score'] if len(results)>1 else 0.
        separation=best['score']/max(runner,1.)
        accepted=best['score']>=self.threshold and separation>=self.margin and best['inliers']>=12 and best['coverage']>=.04 and best['inlier_ratio']>=.3
        # Evidence strength, deliberately NOT an empirically calibrated probability.
        confidence=min(1.,best['score']/(2*self.threshold))*min(1.,separation/(2*self.margin))
        return dict(status='matched' if accepted else 'unknown',card_id=best['card_id'] if accepted else None,reference_asset=best['reference_asset'] if accepted else None,confidence=round(confidence,4) if accepted else None,confidence_kind='heuristic_match_strength_not_probability',best_candidate_strength=round(confidence,4),reason='geometric_match' if accepted else ('ambiguous' if best['score']>=self.threshold and separation<self.margin else 'insufficient_evidence'),candidates=results,shortlist=[self.cards[i]['card_id'] for i in shortlist],query_shape=list(shape),timing_ms=dict(retrieval=round(retrieval_ms,2),matching=round((time.perf_counter()-start)*1000,2)))
    def match(self,source):
        start=time.perf_counter();xy,d,shape,preprocess=self.extract_timed(source)
        result=self.match_features(xy,d,shape)
        match_after_crop = preprocess['features'] + result['timing_ms']['matching']
        result['timing_ms'].update(decode=round(preprocess['decode'],2),
                                   crop=round(preprocess['crop'],2),
                                   features=round(preprocess['features'],2),
                                   crop_detected=preprocess['crop_detected'],
                                   match_after_crop=round(match_after_crop,2),
                                   total_with_crop=round((time.perf_counter()-start)*1000,2))
        return result

def main():
    p=argparse.ArgumentParser();sub=p.add_subparsers(dest='command',required=True)
    b=sub.add_parser('build');b.add_argument('--enrollment',default='artifacts/enrollment.json');b.add_argument('--index',default='artifacts/index');b.add_argument('--words',type=int,default=1024)
    m=sub.add_parser('match');m.add_argument('image');m.add_argument('--index',default='artifacts/index');m.add_argument('--top-k',type=int,default=8)
    a=p.parse_args()
    if a.command=='build':build(a.enrollment,a.index,a.words)
    else:print(json.dumps(Matcher(a.index,a.top_k).match(a.image),indent=2))
if __name__=='__main__':main()
