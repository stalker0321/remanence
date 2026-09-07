"""Symmetric image preprocessing and features. No manual annotations."""
import io
import cv2
import numpy as np
from PIL import Image, ImageOps

cv2.setNumThreads(1)

def decode(source, max_side=1200):
    with Image.open(io.BytesIO(source) if isinstance(source, bytes) else source) as im:
        if im.width * im.height > 40_000_000:
            raise ValueError('Image exceeds 40 megapixels')
        if max_side < 1200:
            im.draft('RGB', (max_side, max_side))
        im = ImageOps.exif_transpose(im).convert('RGB')
        im.thumbnail((max_side, max_side), Image.Resampling.LANCZOS)
        return cv2.cvtColor(np.array(im), cv2.COLOR_RGB2BGR)


def _ordered(points):
    points = np.asarray(points, np.float32).reshape(4, 2)
    center = points.mean(axis=0)
    points = points[np.argsort(np.arctan2(points[:, 1] - center[1],
                                         points[:, 0] - center[0]))]
    return np.roll(points, -np.argmin(points.sum(axis=1)), axis=0)


def _grabcut_quad(image):
    """Find a likely foreground rectangle from pixels alone.

    The initialization is a fixed image-relative rectangle; it is not a card
    annotation. A conservative fallback to the full frame is intentional when
    the segmentation is not rectangular enough to trust.
    """
    height, width = image.shape[:2]
    # Segmentation only needs a coarse silhouette; descriptors remain at 960 px.
    small_width = min(240, width)
    small_height = max(32, round(height * small_width / width))
    small = cv2.resize(image, (small_width, small_height), interpolation=cv2.INTER_AREA)
    mask = np.zeros((small_height, small_width), np.uint8)
    bg_model = np.zeros((1, 65), np.float64)
    fg_model = np.zeros((1, 65), np.float64)
    cv2.setRNGSeed(42)
    inset_x, inset_y = max(2, round(small_width * .04)), max(2, round(small_height * .04))
    rect = (inset_x, inset_y, small_width - 2 * inset_x,
            small_height - 2 * inset_y)
    cv2.grabCut(small, mask, rect, bg_model, fg_model, 1, cv2.GC_INIT_WITH_RECT)
    foreground = np.where((mask == cv2.GC_FGD) | (mask == cv2.GC_PR_FGD), 255, 0).astype(np.uint8)
    foreground = cv2.morphologyEx(foreground, cv2.MORPH_OPEN,
                                  np.ones((5, 5), np.uint8))
    foreground = cv2.morphologyEx(foreground, cv2.MORPH_CLOSE,
                                  np.ones((9, 9), np.uint8))
    contours, _ = cv2.findContours(foreground, cv2.RETR_EXTERNAL,
                                   cv2.CHAIN_APPROX_SIMPLE)
    if not contours:
        return None
    contour = max(contours, key=cv2.contourArea)
    area_fraction = cv2.contourArea(contour) / max(1, small_width * small_height)
    if not .06 <= area_fraction <= .88:
        return None
    rectangle = cv2.minAreaRect(contour)
    box = cv2.boxPoints(rectangle)
    box_area = cv2.contourArea(box.astype(np.float32))
    if box_area <= 0 or cv2.contourArea(contour) / box_area < .60:
        return None
    side_lengths = np.linalg.norm(box - np.roll(box, 1, axis=0), axis=1)
    if side_lengths.max() / max(side_lengths.min(), 1.) > 5.0:
        return None
    center = box.mean(axis=0) / [small_width, small_height]
    if np.linalg.norm(center - .5) > .35:
        return None
    scale = np.float32([width / small_width, height / small_height])
    return _ordered(box) * scale


def auto_crop(image):
    """Automatically rectify a likely postcard; fall back to the full frame."""
    corners = _grabcut_quad(image)
    if corners is None:
        return image, False
    top_left, top_right, bottom_right, bottom_left = corners
    out_width = int(max(np.linalg.norm(top_right - top_left),
                        np.linalg.norm(bottom_right - bottom_left)))
    out_height = int(max(np.linalg.norm(bottom_left - top_left),
                         np.linalg.norm(bottom_right - top_right)))
    if min(out_width, out_height) < 40:
        return image, False
    destination = np.float32([[0, 0], [out_width - 1, 0],
                              [out_width - 1, out_height - 1], [0, out_height - 1]])
    transform = cv2.getPerspectiveTransform(corners.astype(np.float32), destination)
    crop = cv2.warpPerspective(image, transform, (out_width, out_height))
    return crop, True

def features(im):
    gray=cv2.cvtColor(im,cv2.COLOR_BGR2GRAY)
    sift=cv2.SIFT_create(nfeatures=0,contrastThreshold=.018,edgeThreshold=12)
    candidates=sift.detect(gray,None)
    buckets={}
    h,w=gray.shape
    for point in sorted(candidates,key=lambda p:p.response,reverse=True):
        cell=(min(5,int(point.pt[0]*6/w)),min(5,int(point.pt[1]*6/h)))
        group=buckets.setdefault(cell,[])
        if len(group)<45:group.append(point)
    selected=[point for group in buckets.values() for point in group]
    k,d=sift.compute(gray,selected)
    if d is None:return np.empty((0,2),np.float32),np.empty((0,128),np.uint8)
    return np.float32([x.pt for x in k]).reshape(-1,2),np.clip(np.rint(d),0,255).astype(np.uint8)

def root(d):
    d=d.astype(np.float32)
    return np.sqrt(d/np.maximum(d.sum(axis=1,keepdims=True),1e-12))

def verify(qxy,qd,ref,qindex=None):
    rxy,rd=ref['xy'],root(ref['descriptors'])
    empty=dict(score=0.,inliers=0,matches=0,inlier_ratio=0.,coverage=0.,polygon=None)
    if min(len(qd),len(rd))<4:return empty
    if qindex is None:
        pairs=cv2.BFMatcher().knnMatch(rd,qd,k=2)
        good=[a for a,b in pairs if a.distance<.75*b.distance]
    else:
        indices,distances=qindex.knnSearch(rd,2,params=dict(checks=96))
        good=[cv2.DMatch(i,int(indices[i,0]),float(distances[i,0])) for i in range(len(rd)) if distances[i,0]<.75**2*distances[i,1]]
    # Unique query points; repeated pattern features must not inflate evidence.
    used=set(); unique=[]
    for m in sorted(good,key=lambda m:m.distance):
        key=tuple(np.rint(qxy[m.trainIdx]).astype(int))
        if key not in used: used.add(key);unique.append(m)
    good=unique
    if len(good)<6:return {**empty,'matches':len(good)}
    a=np.float32([rxy[m.queryIdx] for m in good]);b=np.float32([qxy[m.trainIdx] for m in good])
    cv2.setRNGSeed(42)
    H,mask=cv2.findHomography(a,b,cv2.USAC_MAGSAC,4.,maxIters=3000,confidence=.999)
    if H is None or mask is None:return {**empty,'matches':len(good)}
    keep=mask.ravel().astype(bool); n=int(keep.sum())
    if n<4:return {**empty,'matches':len(good)}
    h,w=map(int,ref['shape'])
    hull=cv2.convexHull(a[keep]); coverage=abs(cv2.contourArea(hull))/(w*h)
    left,top=a[keep].min(axis=0);right,bottom=a[keep].max(axis=0)
    # Bounding box of observed support; the full photograph is not the card boundary.
    corners=np.float32([[left,top],[right,top],[right,bottom],[left,bottom]])
    poly=cv2.perspectiveTransform(corners[None],H)[0]
    if not np.isfinite(poly).all() or not cv2.isContourConvex(poly): return {**empty,'matches':len(good)}
    area=cv2.contourArea(poly,oriented=True)
    lengths=np.linalg.norm(poly-np.roll(poly,1,axis=0),axis=1)
    if area<300 or lengths.max()/max(lengths.min(),1)>8: return {**empty,'matches':len(good)}
    ratio=n/len(good)
    score=n * min(1.,coverage/.20) * min(1.,ratio/.5)
    return dict(score=float(score),inliers=n,matches=len(good),inlier_ratio=round(ratio,4),coverage=round(coverage,4),polygon=poly.round(1).tolist())
