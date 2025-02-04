//
// Copyright (c) 2019-2021 Red Hat, Inc.
// This program and the accompanying materials are made
// available under the terms of the Eclipse Public License 2.0
// which is available at https://www.eclipse.org/legal/epl-2.0/
//
// SPDX-License-Identifier: EPL-2.0
//
// Contributors:
//   Red Hat, Inc. - initial API and implementation
//
package server

import (
	"github.com/eclipse-che/che-operator/pkg/deploy"
	"github.com/eclipse-che/che-operator/pkg/deploy/gateway"
	"github.com/eclipse-che/che-operator/pkg/util"
	routev1 "github.com/openshift/api/route/v1"
	networking "k8s.io/api/networking/v1"
	"sigs.k8s.io/controller-runtime/pkg/reconcile"
)

type CheHostReconciler struct {
	deploy.Reconcilable
}

func NewCheHostReconciler() *CheHostReconciler {
	return &CheHostReconciler{}
}

func (s *CheHostReconciler) Reconcile(ctx *deploy.DeployContext) (reconcile.Result, bool, error) {
	done, err := s.syncCheService(ctx)
	if !done {
		return reconcile.Result{}, false, err
	}

	cheHost, done, err := s.exposeCheEndpoint(ctx)
	if !done {
		return reconcile.Result{}, false, err
	}

	done, err = s.updateCheURL(cheHost, ctx)
	if !done {
		return reconcile.Result{}, false, err
	}

	return reconcile.Result{}, true, nil
}

func (s *CheHostReconciler) Finalize(ctx *deploy.DeployContext) bool {
	return true
}

func (s *CheHostReconciler) syncCheService(ctx *deploy.DeployContext) (bool, error) {
	portName := []string{"http"}
	portNumber := []int32{8080}

	if ctx.CheCluster.Spec.Metrics.Enable {
		portName = append(portName, "metrics")
		portNumber = append(portNumber, deploy.DefaultCheMetricsPort)
	}

	cheDebug := util.GetValue(ctx.CheCluster.Spec.Server.CheDebug, deploy.DefaultCheDebug)
	if cheDebug == "true" {
		portName = append(portName, "debug")
		portNumber = append(portNumber, deploy.DefaultCheDebugPort)
	}

	spec := deploy.GetServiceSpec(ctx, deploy.CheServiceName, portName, portNumber, getComponentName(ctx))
	return deploy.Sync(ctx, spec, deploy.ServiceDefaultDiffOpts)
}

func (s CheHostReconciler) exposeCheEndpoint(ctx *deploy.DeployContext) (string, bool, error) {
	if !util.IsOpenShift {
		_, done, err := deploy.SyncIngressToCluster(
			ctx,
			getComponentName(ctx),
			ctx.CheCluster.Spec.Server.CheHost,
			"",
			gateway.GatewayServiceName,
			8080,
			ctx.CheCluster.Spec.Server.CheServerIngress,
			getComponentName(ctx))
		if !done {
			return "", false, err
		}

		ingress := &networking.Ingress{}
		exists, err := deploy.GetNamespacedObject(ctx, getComponentName(ctx), ingress)
		if !exists {
			return "", false, err
		}

		return ingress.Spec.Rules[0].Host, true, nil
	}

	done, err := deploy.SyncRouteToCluster(
		ctx,
		getComponentName(ctx),
		ctx.CheCluster.Spec.Server.CheHost,
		"/",
		gateway.GatewayServiceName,
		8080,
		ctx.CheCluster.Spec.Server.CheServerRoute,
		getComponentName(ctx))
	if !done {
		return "", false, err
	}

	route := &routev1.Route{}
	exists, err := deploy.GetNamespacedObject(ctx, getComponentName(ctx), route)
	if !exists {
		return "", false, err
	}

	return route.Spec.Host, true, nil
}

func (s CheHostReconciler) updateCheURL(cheHost string, ctx *deploy.DeployContext) (bool, error) {
	var cheUrl = "https://" + cheHost
	if ctx.CheCluster.Status.CheURL != cheUrl {
		ctx.CheCluster.Status.CheURL = cheUrl
		err := deploy.UpdateCheCRStatus(ctx, getComponentName(ctx)+" server URL", cheUrl)
		return err == nil, err
	}

	return true, nil
}
