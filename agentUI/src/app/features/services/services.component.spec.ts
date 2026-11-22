import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed, fakeAsync, tick } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { apiInterceptor } from '../../core/api/api.interceptor';
import { ServicesComponent } from './services.component';

describe('ServicesComponent', () => {
  let httpMock: HttpTestingController;

  beforeEach(async () => {
    localStorage.removeItem('healops.selectedEnvironmentId');
    await TestBed.configureTestingModule({
      imports: [ServicesComponent],
      providers: [
        provideRouter([]),
        provideHttpClient(withInterceptors([apiInterceptor])),
        provideHttpClientTesting(),
      ],
    }).compileComponents();

    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('debounces service filter typing by 300ms before issuing API requests', fakeAsync(() => {
    const fixture = TestBed.createComponent(ServicesComponent);
    fixture.detectChanges();

    // Initial topology load
    const topoReq = httpMock.expectOne('/api/v1/topology?environmentId=local');
    topoReq.flush({
      environmentId: 'local',
      from: '2026-11-22T11:00:00Z',
      to: '2026-11-22T12:00:00Z',
      dataSource: 'DERIVED_FROM_EVIDENCE_AND_INCIDENTS',
      nodes: [],
      edges: [],
    });
    tick();

    httpMock.expectOne('/api/v1/traces?environmentId=local&limit=20').flush({
      environmentId: 'local',
      from: '2026-11-22T11:00:00Z',
      to: '2026-11-22T12:00:00Z',
      limit: 20,
      dataSource: 'LOCAL_EVIDENCE',
      traces: [],
    });
    httpMock.expectOne('/api/v1/logs?environmentId=local&limit=50').flush({
      environmentId: 'local',
      from: '2026-11-22T11:00:00Z',
      to: '2026-11-22T12:00:00Z',
      limit: 50,
      truncated: false,
      dataSource: 'LOCAL_EVIDENCE',
      records: [],
    });
    tick();

    const comp = fixture.componentInstance;
    comp.onServiceInput('d');
    comp.onServiceInput('de');
    comp.onServiceInput('demo');
    tick(200);
    // Before 300ms debounce expires, no scoped requests should be sent
    httpMock.expectNone('/api/v1/metrics/range?environmentId=local&service=demo&metric=latency_ms');

    comp.onServiceInput('demo-workload');
    tick(300);

    // Only the final debounced service triggers API calls
    httpMock.expectOne('/api/v1/traces?environmentId=local&service=demo-workload&limit=20').flush({
      environmentId: 'local',
      from: '2026-11-22T11:00:00Z',
      to: '2026-11-22T12:00:00Z',
      limit: 20,
      dataSource: 'JAEGER_V3',
      traces: [],
    });
    httpMock.expectOne('/api/v1/logs?environmentId=local&service=demo-workload&limit=50').flush({
      environmentId: 'local',
      from: '2026-11-22T11:00:00Z',
      to: '2026-11-22T12:00:00Z',
      limit: 50,
      truncated: false,
      dataSource: 'LOKI',
      records: [],
    });
    httpMock.expectOne('/api/v1/metrics/range?environmentId=local&service=demo-workload&metric=latency_ms').flush({
      environmentId: 'local',
      service: 'demo-workload',
      metric: 'latency_ms',
      unit: 'ms',
      from: '2026-11-22T11:00:00Z',
      to: '2026-11-22T12:00:00Z',
      requestedStepSeconds: 60,
      effectiveStepSeconds: 60,
      downsampled: false,
      dataSource: 'PROMETHEUS',
      series: [],
    });
    tick();
    expect(comp.metricData()?.dataSource).toBe('PROMETHEUS');
    expect(comp.traces()?.dataSource).toBe('JAEGER_V3');
    expect(comp.logs()?.dataSource).toBe('LOKI');
  }));
});
